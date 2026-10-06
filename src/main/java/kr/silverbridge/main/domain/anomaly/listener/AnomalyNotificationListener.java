package kr.silverbridge.main.domain.anomaly.listener;

import kr.silverbridge.main.domain.anomaly.dto.DetectedTypeLabel;
import kr.silverbridge.main.domain.anomaly.event.AnomalyDetectedEvent;
import kr.silverbridge.main.domain.anomaly.service.AnomalyNotificationCooldown;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.global.enums.DetectedType;
import kr.silverbridge.main.global.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 이상감지 이력 적재 후 보호자·피보호자에게 알림을 발송하는 리스너.
 *
 * <p>{@code SosNotificationListener}와 동일 패턴이다: AFTER_COMMIT(이력 커밋 후에만 발송, 롤백 시 미발송) +
 * {@code @Async("urgentNotificationExecutor")}(발송 지연이 AI 신호 처리 스레드를 붙잡지 않도록 분리. 긴급 알림
 * 전용 풀이라 일반 알림이 밀려도 그 뒤에 줄 서지 않는다 - 2026-10-02 QA XCUT-G11).</p>
 *
 * <p><b>수신자</b> = ACTIVE 보호자 전원 + <b>피보호자 본인</b>. 화재는 집 안 당사자의 대피가 최우선이라 본인에게도
 * 보낸다(설계 D-1). 본인에겐 대피를 재촉하는 별도 문구를 쓰고, 쿨다운도 더 짧게 적용한다.</p>
 *
 * <p><b>발송 경로</b>는 두 갈래:</p>
 * <ul>
 *   <li><b>WebSocket</b>({@code anomaly-detected}) — 채널 추상화 밖, 사용자 설정과 무관하게 항상 발송.</li>
 *   <li><b>{@link NotificationDispatcher}</b> + {@link NotificationType#ANOMALY_DETECTED} —
 *       FCM은 설정을 무시하고 항상, SMS·알림톡은 사용자가 켠 경우에만 추가 발송. 어느 채널로도 전달되지 않으면
 *       문자를 대신 보낸다(2026-10-06, 문구는 {@link #smsFallbackText}).</li>
 * </ul>
 *
 * <p>수신자별 발송을 try/catch로 감싸 한 명 실패가 나머지 발송을 막지 않게 격리한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnomalyNotificationListener {

    private static final String TITLE = "이상 상황 감지";

    /** AI analyzedAt은 UTC 오프셋이라 표시 직전 KST로 변환한다(컨테이너 TZ에 의존하지 않는다). */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DETECTED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ConnectionService connectionService;
    private final WebSocketEventPublisher webSocketEventPublisher;
    private final NotificationDispatcher notificationDispatcher;
    private final AnomalyNotificationCooldown cooldown;

    @Async("urgentNotificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleAnomalyDetected(AnomalyDetectedEvent event) {
        List<String> recipients = new ArrayList<>(connectionService.getActiveGuardianIds(event.wardId()));
        recipients.add(event.wardId());   // 피보호자 본인 — 화재 현장 당사자 (D-1)

        // detectedType은 enum 그대로 유지(FE 계약). wardName·location·detectedTypeLabel·detectedAt은 화면 표시용 —
        // 알림톡 템플릿 변수로도 쓰인다(AlimtalkProperties.variables와 키 이름이 일치해야 바인딩된다).
        // Map.of는 10쌍이 상한이라 Map.ofEntries를 쓴다 — 여기가 정확히 그 경계였다.
        Map<String, String> data = Map.ofEntries(
                Map.entry("type", NotificationType.ANOMALY_DETECTED.name()),
                Map.entry("wardId", event.wardId()),
                Map.entry("wardName", event.wardName()),
                Map.entry("location", event.cameraLabel()),
                Map.entry("sessionId", event.sessionId()),
                Map.entry("detectedType", event.detectedType().name()),
                Map.entry("detectedTypeLabel", label(event.detectedType())),
                Map.entry("detectedAt", formatDetectedAt(event.detectedAt())),
                Map.entry("anomalyEventId", String.valueOf(event.anomalyEventId())),
                // 보호자가 알림에서 바로 오탐 응답을 하려면 판정 단위(상황) 식별자가 필요하다.
                Map.entry("incidentId", String.valueOf(event.incidentId())));

        int sent = 0;
        for (String userId : recipients) {
            boolean self = userId.equals(event.wardId());
            try {
                if (!cooldown.tryAcquire(userId, event.sessionId(), event.detectedType(), self)) {
                    log.debug("[ANOMALY] 알림 쿨다운 — 발송 생략(이력은 적재됨): userId={}, anomalyEventId={}",
                            userId, event.anomalyEventId());
                    continue;
                }

                webSocketEventPublisher.sendToUser(userId, "anomaly-detected", data);
                // 본인은 별도 타입으로 보낸다 — 승인된 알림톡 템플릿이 보호자용 문구라 본인에게 나가면 안 된다.
                // (data["type"]은 계속 ANOMALY_DETECTED — 클라이언트 계약은 그대로 둔다)
                notificationDispatcher.dispatch(userId, event.wardId(),
                        self ? NotificationType.ANOMALY_DETECTED_SELF : NotificationType.ANOMALY_DETECTED,
                        NotificationContent.of(TITLE, body(event, self), data, smsFallbackText(event, self)));
                sent++;
            } catch (Exception e) {
                // 한 수신자 발송 실패가 나머지 발송을 막지 않도록 격리. 원인 진단을 위해 스택 포함
                log.error("[ANOMALY] 알림 발송 실패: userId={}, anomalyEventId={}",
                        userId, event.anomalyEventId(), e);
            }
        }

        log.info("[ANOMALY] 이상감지 알림 발송: anomalyEventId={}, 대상={}명, 발송={}건",
                event.anomalyEventId(), recipients.size(), sent);
    }

    /**
     * 알림 본문. 시니어/4050 대상이라 완곡어법 없이 "감지되었습니다"로 명시한다(설계 D-3).
     * 본인에게는 상황 통지에 그치지 않고 종류에 맞는 행동 안내를 함께 준다({@link #selfGuidance}).
     */
    private String body(AnomalyDetectedEvent event, boolean self) {
        String what = DetectedTypeLabel.withSubjectParticle(event.detectedType());
        if (self) {
            return event.cameraLabel() + "에서 " + what + " 감지되었습니다. " + selfGuidance(event.detectedType());
        }
        return event.wardName() + "님 댁 " + event.cameraLabel() + "에서 " + what + " 감지되었습니다."
                + guardianGuidance(event.detectedType());
    }

    /**
     * 보호자 수신분의 행동 안내(앞에 공백 포함, 없으면 빈 문자열). 화재는 기존 문구 그대로 두고, 흉기·낙상은
     * 보호자가 바로 연락해 확인하도록 안내한다. 신고는 안내 문구일 뿐 서버가 발신하지 않는다.
     */
    private static String guardianGuidance(DetectedType type) {
        return switch (type) {
            case WEAPON -> " 바로 연락해 안전을 확인하고, 위험하면 112에 신고해 주세요.";
            case FALL -> " 바로 연락해 안전을 확인해 주세요.";
            default -> "";
        };
    }

    /**
     * 본인 수신분의 행동 안내. 화재는 대피, 흉기는 피신·신고, 낙상은 대피가 아니라 도움 요청이다
     * (넘어진 사람에게 "대피"는 맞지 않는다). 신고는 안내 문구일 뿐 서버가 발신하지 않는다.
     */
    private static String selfGuidance(DetectedType type) {
        return switch (type) {
            case WEAPON -> "안전한 곳으로 피하고 112에 연락해 주세요.";
            case FALL -> "괜찮으시면 보호자에게 연락해 주세요. 도움이 필요하면 SOS 버튼을 눌러 주세요.";
            default -> "안전한 곳으로 대피해 주세요.";
        };
    }

    private static String selfSmsGuidance(DetectedType type) {
        return switch (type) {
            case WEAPON -> "안전한 곳으로 피하고 112에 연락해 주세요.";
            case FALL -> "도움이 필요하면 보호자에게 연락해 주세요.";
            default -> "안전한 곳으로 대피해 주세요.";
        };
    }

    /**
     * 푸시가 전달되지 않아 문자로 대신 나갈 때의 문구(SMS 길이 안). 이름·위치가 비면 그 부분만 뺀다.
     * 본인에게는 종류별 행동 안내를 함께 준다. 푸시 본문과 달리 앱 이름을 앞에 붙여 발신처를 알린다.
     */
    static String smsFallbackText(AnomalyDetectedEvent event, boolean self) {
        String what = label(event.detectedType());
        String place = hasText(event.cameraLabel()) ? event.cameraLabel() + "에서" : "등록된 카메라에서";
        if (self) {
            return "[실버브릿지] " + place + " " + what + " 감지. " + selfSmsGuidance(event.detectedType());
        }
        String home = hasText(event.wardName()) ? event.wardName() + "님 댁 " : "";
        String guidance = guardianGuidance(event.detectedType());
        if (guidance.isEmpty()) {
            return "[실버브릿지] " + home + place + " " + what + " 감지. 앱에서 확인해 주세요.";
        }
        // 흉기·낙상은 푸시 본문과 같은 문구(앱 이름만 앞에 붙인다)
        return "[실버브릿지] " + home + place + " " + DetectedTypeLabel.withSubjectParticle(event.detectedType())
                + " 감지되었습니다." + guidance;
    }

    private static boolean hasText(String v) {
        return v != null && !v.isBlank();
    }

    /**
     * 감지 시각 표기(KST). 알림톡 승인 템플릿의 {@code #{detectedAt}}에 그대로 들어간다.
     *
     * <p>AI fallback 페이로드엔 {@code analyzedAt}이 없어 null일 수 있는데, 그대로 두면 승인 문구가
     * "감지 시각: "으로 비어 나간다. 알림은 감지 직후(AFTER_COMMIT) 발송돼 오차가 초 단위라
     * <b>표시에 한해</b> 발송 시각으로 대체한다 — 이력({@code anomaly_event.detected_at})은 NULL 그대로 두어
     * "AI가 알려준 시각"과 "우리가 받은 시각"의 구분을 유지한다.</p>
     */
    private String formatDetectedAt(OffsetDateTime detectedAt) {
        OffsetDateTime shown = (detectedAt != null) ? detectedAt : OffsetDateTime.now();
        return shown.atZoneSameInstant(DISPLAY_ZONE).format(DETECTED_AT_FORMAT);
    }

    // 알림 문구용 표기. DetectedType(global enum)은 AI 계약을 표현하는 값이라 UI 문자열을 넣지 않는다.
    /** 이력·재촉 화면과 같은 단어를 쓰도록 표시 문구는 한 곳({@link DetectedTypeLabel})에서만 정한다. */
    private static String label(DetectedType detectedType) {
        return DetectedTypeLabel.of(detectedType);
    }
}

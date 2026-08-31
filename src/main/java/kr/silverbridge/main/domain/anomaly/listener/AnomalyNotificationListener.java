package kr.silverbridge.main.domain.anomaly.listener;

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
 * {@code @Async("notificationExecutor")}(발송 지연이 AI 신호 처리 스레드를 붙잡지 않도록 분리).</p>
 *
 * <p><b>수신자</b> = ACTIVE 보호자 전원 + <b>피보호자 본인</b>. 화재는 집 안 당사자의 대피가 최우선이라 본인에게도
 * 보낸다(설계 D-1). 본인에겐 대피를 재촉하는 별도 문구를 쓰고, 쿨다운도 더 짧게 적용한다.</p>
 *
 * <p><b>발송 경로</b>는 두 갈래:</p>
 * <ul>
 *   <li><b>WebSocket</b>({@code anomaly-detected}) — 채널 추상화 밖, 사용자 설정과 무관하게 항상 발송.</li>
 *   <li><b>{@link NotificationDispatcher}</b> + {@link NotificationType#ANOMALY_DETECTED} —
 *       FCM은 설정을 무시하고 항상, SMS·알림톡은 사용자가 켠 경우에만 추가 발송.</li>
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

    @Async("notificationExecutor")
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
                notificationDispatcher.dispatch(userId,
                        self ? NotificationType.ANOMALY_DETECTED_SELF : NotificationType.ANOMALY_DETECTED,
                        NotificationContent.of(TITLE, body(event, self), data));
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
     * 본인에게는 상황 통지에 그치지 않고 대피 행동을 함께 지시한다.
     */
    private String body(AnomalyDetectedEvent event, boolean self) {
        String what = label(event.detectedType());
        if (self) {
            return event.cameraLabel() + "에서 " + what + "가 감지되었습니다. 안전한 곳으로 대피해 주세요.";
        }
        return event.wardName() + "님 댁 " + event.cameraLabel() + "에서 " + what + "가 감지되었습니다.";
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
    private String label(DetectedType detectedType) {
        return switch (detectedType) {
            case FIRE -> "화재";
            case SMOKE -> "연기";
            default -> "이상 상황";
        };
    }
}

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

    private final ConnectionService connectionService;
    private final WebSocketEventPublisher webSocketEventPublisher;
    private final NotificationDispatcher notificationDispatcher;
    private final AnomalyNotificationCooldown cooldown;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleAnomalyDetected(AnomalyDetectedEvent event) {
        List<String> recipients = new ArrayList<>(connectionService.getActiveGuardianIds(event.wardId()));
        recipients.add(event.wardId());   // 피보호자 본인 — 화재 현장 당사자 (D-1)

        Map<String, String> data = Map.of(
                "type", NotificationType.ANOMALY_DETECTED.name(),
                "wardId", event.wardId(),
                "sessionId", event.sessionId(),
                "detectedType", event.detectedType().name(),
                "anomalyEventId", String.valueOf(event.anomalyEventId()));

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
                notificationDispatcher.dispatch(userId, NotificationType.ANOMALY_DETECTED,
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

    // 알림 문구용 표기. DetectedType(global enum)은 AI 계약을 표현하는 값이라 UI 문자열을 넣지 않는다.
    private String label(DetectedType detectedType) {
        return switch (detectedType) {
            case FIRE -> "화재";
            case SMOKE -> "연기";
            default -> "이상 상황";
        };
    }
}

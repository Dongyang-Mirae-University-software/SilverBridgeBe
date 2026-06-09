package kr.silverbridge.main.domain.sos.listener;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.sos.event.SosTriggeredEvent;
import kr.silverbridge.main.domain.sos.service.SosNotificationCooldown;
import kr.silverbridge.main.global.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;

/**
 * 피보호자 SOS 발생 이벤트를 수신해 ACTIVE 보호자 전원에게 긴급 알림을 발송하는 리스너.
 *
 * <p>{@code ConnectionNotificationListener}와 동일 패턴이다: {@code @TransactionalEventListener(AFTER_COMMIT)}로
 * 이력 저장 커밋 후에만 동작하고(롤백 시 미발송), {@code @Async("notificationExecutor")}로 발송 지연이
 * HTTP 응답 시간에 포함되지 않도록 분리한다.</p>
 *
 * <p>발송은 두 갈래다:</p>
 * <ul>
 *   <li><b>WebSocket</b>({@code sos-triggered}) — 채널 추상화 밖, 항상 발송.</li>
 *   <li><b>{@link NotificationDispatcher}</b> + {@link NotificationType#WARD_SOS}(필수 알림) —
 *       보호자의 알림 설정을 무시하고 강제 발송(긴급 알림이므로 끌 수 없음).</li>
 * </ul>
 *
 * <p>보호자가 여러 명이면 각 보호자 발송을 try/catch로 감싸 한 명 발송 실패가 나머지 보호자 발송을 막지 않게
 * 격리한다(실패 격리). 연결된 ACTIVE 보호자가 없으면 이력만 남고 발송 없이 종료한다.</p>
 *
 * <p>연타 시 보호자 알림 폭주를 막기 위해 {@link SosNotificationCooldown}으로 동일 피보호자 알림에 쿨다운을
 * 둔다 — 단 쿨다운은 <b>알림</b>에만 적용되며 이력({@code sos_events})은 항상 보존된다. 쿨다운 인프라 장애 시에는
 * 긴급 우선 원칙에 따라 알림을 발송한다(fail-open).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SosNotificationListener {

    private final ConnectionService connectionService;
    private final WebSocketEventPublisher webSocketEventPublisher;
    private final NotificationDispatcher notificationDispatcher;
    private final SosNotificationCooldown cooldown;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSosTriggered(SosTriggeredEvent event) {
        List<String> guardianIds = connectionService.getActiveGuardianIds(event.wardId());
        if (guardianIds.isEmpty()) {
            log.info("SOS 알림 대상 보호자 없음(이력은 보존): wardId={}, sosEventId={}",
                    event.wardId(), event.sosEventId());
            return;
        }

        // 연타 알림 폭주 방지 — 쿨다운 내 재요청은 알림만 생략(이력은 이미 저장됨). 긴급 재요청을 차단(429)하지는 않는다.
        if (!cooldown.tryAcquire(event.wardId())) {
            log.info("SOS 알림 쿨다운 — 직전 발송 후 재요청이라 알림 생략(이력은 보존): wardId={}, sosEventId={}",
                    event.wardId(), event.sosEventId());
            return;
        }

        String body = event.wardName() + "님이 긴급 도움을 요청했습니다.";
        int sent = 0;
        for (String guardianId : guardianIds) {
            try {
                webSocketEventPublisher.sendToUser(guardianId, "sos-triggered",
                        Map.of("wardId", event.wardId(),
                                "wardName", event.wardName(),
                                "sosEventId", String.valueOf(event.sosEventId())));

                notificationDispatcher.dispatch(guardianId, NotificationType.WARD_SOS,
                        NotificationContent.of("긴급 SOS", body,
                                Map.of("type", "WARD_SOS",
                                        "wardId", event.wardId(),
                                        "sosEventId", String.valueOf(event.sosEventId()))));
                sent++;
            } catch (Exception e) {
                // 한 보호자 발송 실패가 나머지 보호자 발송을 막지 않도록 격리
                log.error("SOS 알림 발송 실패: guardianId={}, sosEventId={}, error={}",
                        guardianId, event.sosEventId(), e.getMessage());
            }
        }
        log.info("SOS 긴급 알림 발송: sosEventId={}, 대상 보호자={}명, 발송={}건",
                event.sosEventId(), guardianIds.size(), sent);
    }
}

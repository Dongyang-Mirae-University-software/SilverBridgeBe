package kr.silverbridge.main.domain.sos.listener;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.sos.event.SosAcknowledgedEvent;
import kr.silverbridge.main.global.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * SOS 처리 결과(ACK) 기록 후 관련 화면을 실시간 갱신하는 리스너.
 *
 * <p>{@code SosNotificationListener}와 동일하게 AFTER_COMMIT + {@code @Async}다 — 기록이 커밋된 뒤에만
 * 발송하고(롤백 시 미발송), 발송 지연이 ACK 응답 시간에 포함되지 않게 분리한다.</p>
 *
 * <p><b>WebSocket만 발송한다</b>({@code sos-acknowledged}). FCM·SMS·알림톡은 보내지 않는다 — 이미 종료된
 * 긴급 상황의 상태 갱신이라 푸시로 알릴 가치보다 소음이 크다. 채널 추상화
 * ({@code NotificationDispatcher})를 거치지 않는 이유도 같다.</p>
 *
 * <p>수신자는 <b>해당 피보호자의 ACTIVE 보호자 전원 + 피보호자 본인</b>이다. 처리한 보호자 본인도 포함한다
 * (다른 기기·탭의 화면 동기화). 피보호자에게는 "보호자가 확인했다"는 안심 신호가 된다. 토픽
 * {@code /topic/{userId}/sos-acknowledged}는 STOMP 구독 인터셉터의 범용 {@code {userId}==세션} 검증으로
 * 보호되므로 별도 등록이 필요 없다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SosAckNotificationListener {

    private final ConnectionService connectionService;
    private final WebSocketEventPublisher webSocketEventPublisher;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSosAcknowledged(SosAcknowledgedEvent event) {
        // LinkedHashSet — 보호자 목록에 중복이 있어도 한 번만, 순서는 보호자 → 피보호자 본인
        Set<String> recipients = new LinkedHashSet<>(connectionService.getActiveGuardianIds(event.wardId()));
        recipients.add(event.wardId());

        Map<String, String> payload = Map.of(
                "sosEventId", String.valueOf(event.sosEventId()),
                "wardId", event.wardId(),
                "ackStatus", event.ackStatus().name(),
                "acknowledgedBy", event.guardianId(),
                "acknowledgedByName", event.guardianName());

        // sendToUser는 내부에서 실패를 흡수(WARN)하므로 한 수신자 실패가 나머지를 막지 않는다.
        recipients.forEach(recipient ->
                webSocketEventPublisher.sendToUser(recipient, "sos-acknowledged", payload));

        log.info("SOS 처리 결과 실시간 반영: sosEventId={}, ackStatus={}, 수신자={}명",
                event.sosEventId(), event.ackStatus(), recipients.size());
    }
}

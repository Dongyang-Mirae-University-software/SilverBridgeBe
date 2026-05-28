package kr.silverbridge.main.domain.connection.listener;

import kr.silverbridge.main.domain.connection.event.ConnectionAcceptedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionDisconnectedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionRefusedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionRequestedEvent;
import kr.silverbridge.main.domain.notification.service.FcmService;
import kr.silverbridge.main.global.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * 연결 상태 변경 이벤트 수신 후 WebSocket + FCM 알림을 발송하는 리스너.
 *
 * {@link TransactionPhase#AFTER_COMMIT} 시점에 처리되어 DB 롤백이 발생하면 알림이 나가지 않는다.
 * 또한 {@code @Async("notificationExecutor")}로 별도 스레드에서 발송되어 FCM/WebSocket 지연이
 * HTTP 응답 시간에 포함되지 않는다. 이로써 ConnectionService의 비즈니스 로직과 알림 전달을 완전히 분리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectionNotificationListener {

    private final WebSocketEventPublisher webSocketEventPublisher;
    private final FcmService fcmService;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleRequested(ConnectionRequestedEvent event) {
        webSocketEventPublisher.sendToUser(event.wardId(), "connection-request",
                Map.of("connectionId", event.connectionId(), "from", event.guardianId()));

        // 관계가 있으면 "아들 박민수님이 연결을 요청했어요" / 없으면 기존 fallback 문구
        String body = (event.relation() != null && !event.relation().isBlank())
                ? event.relation() + " " + event.guardianName() + "님이 연결을 요청했어요."
                : event.guardianName() + " 보호자가 연결을 요청했습니다.";

        fcmService.sendToUser(event.wardId(), "연결 요청", body,
                Map.of("type", "CONNECTION_REQUEST",
                        "connectionId", String.valueOf(event.connectionId())));
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleAccepted(ConnectionAcceptedEvent event) {
        webSocketEventPublisher.sendToUser(event.guardianId(), "connection-accepted",
                Map.of("connectionId", event.connectionId()));

        fcmService.sendToUser(event.guardianId(), "연결 수락",
                "피보호자가 연결 요청을 수락했습니다.",
                Map.of("type", "CONNECTION_ACCEPTED",
                        "connectionId", String.valueOf(event.connectionId())));
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleRefused(ConnectionRefusedEvent event) {
        webSocketEventPublisher.sendToUser(event.guardianId(), "connection-refused",
                Map.of("connectionId", event.connectionId()));

        fcmService.sendToUser(event.guardianId(), "연결 거절",
                "연결 요청이 거절되었습니다.",
                Map.of("type", "CONNECTION_REFUSED",
                        "connectionId", String.valueOf(event.connectionId())));
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleDisconnected(ConnectionDisconnectedEvent event) {
        String body = (event.disconnectedBy() == ConnectionDisconnectedEvent.DisconnectedBy.GUARDIAN)
                ? "보호자가 연결을 해제했습니다."
                : "피보호자가 연결을 해제했습니다.";

        webSocketEventPublisher.sendToUser(event.notifyTargetId(), "connection-cancelled",
                Map.of("connectionId", event.connectionId()));

        fcmService.sendToUser(event.notifyTargetId(), "연결 해제", body,
                Map.of("type", "CONNECTION_CANCELLED",
                        "connectionId", String.valueOf(event.connectionId())));
    }
}

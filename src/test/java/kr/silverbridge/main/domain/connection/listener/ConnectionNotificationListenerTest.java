package kr.silverbridge.main.domain.connection.listener;

import kr.silverbridge.main.domain.connection.event.ConnectionAcceptedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionDisconnectedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionRefusedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionRequestedEvent;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.global.websocket.WebSocketEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * ConnectionNotificationListener 단위 테스트.
 *
 * AFTER_COMMIT 핸들러를 직접 호출하여 WebSocket(항상 발송) + 디스패처 위임(설정 채널 발송) 대상·문구를 검증한다.
 * 디스패처 리팩토링 후, 발송 채널 결정은 NotificationDispatcher가 담당하고 리스너는 알림 종류·문구만 책임진다.
 */
@ExtendWith(MockitoExtension.class)
class ConnectionNotificationListenerTest {

    @Mock private WebSocketEventPublisher webSocketEventPublisher;
    @Mock private NotificationDispatcher notificationDispatcher;

    @InjectMocks private ConnectionNotificationListener listener;

    private static final long CONNECTION_ID = 100L;
    private static final String GUARDIAN_ID = "GD0001";
    private static final String WARD_ID = "WD0001";
    private static final String GUARDIAN_NAME = "박보호";

    @Test
    @DisplayName("연결 요청 이벤트(relation 있음) → 피보호자에게 WS + 관계 포함 알림 디스패치")
    void handleRequested_relation있음_관계포함문구() {
        ConnectionRequestedEvent event =
                new ConnectionRequestedEvent(CONNECTION_ID, GUARDIAN_ID, WARD_ID, GUARDIAN_NAME, "아들");

        listener.handleRequested(event);

        verify(webSocketEventPublisher).sendToUser(eq(WARD_ID), eq("connection-request"), anyMap());

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(eq(WARD_ID), eq(NotificationType.CONNECTION_REQUEST), captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("연결 요청");
        assertThat(captor.getValue().body()).isEqualTo("아들 박보호님이 연결을 요청했어요.");
        assertThat(captor.getValue().data()).containsEntry("type", "CONNECTION_REQUEST");
    }

    @Test
    @DisplayName("연결 요청 이벤트(relation 없음) → fallback 문구로 디스패치")
    void handleRequested_relation없음_fallback문구() {
        ConnectionRequestedEvent event =
                new ConnectionRequestedEvent(CONNECTION_ID, GUARDIAN_ID, WARD_ID, GUARDIAN_NAME, null);

        listener.handleRequested(event);

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(eq(WARD_ID), eq(NotificationType.CONNECTION_REQUEST), captor.capture());
        assertThat(captor.getValue().body()).isEqualTo("박보호 보호자가 연결을 요청했습니다.");
    }

    @Test
    @DisplayName("연결 수락 이벤트 → 보호자에게 WS + 수락 알림 디스패치")
    void handleAccepted_보호자에게_수락알림() {
        ConnectionAcceptedEvent event = new ConnectionAcceptedEvent(CONNECTION_ID, GUARDIAN_ID);

        listener.handleAccepted(event);

        verify(webSocketEventPublisher).sendToUser(eq(GUARDIAN_ID), eq("connection-accepted"), anyMap());
        verify(notificationDispatcher).dispatch(
                eq(GUARDIAN_ID), eq(NotificationType.CONNECTION_ACCEPTED),
                eq(NotificationContent.of("연결 수락", "피보호자가 연결 요청을 수락했습니다.",
                        java.util.Map.of("type", "CONNECTION_ACCEPTED",
                                "connectionId", String.valueOf(CONNECTION_ID)))));
    }

    @Test
    @DisplayName("연결 거절 이벤트 → 보호자에게 WS + '연결 요청이 거절되었습니다' 디스패치")
    void handleRefused_보호자에게_거절알림() {
        ConnectionRefusedEvent event = new ConnectionRefusedEvent(CONNECTION_ID, GUARDIAN_ID);

        listener.handleRefused(event);

        verify(webSocketEventPublisher).sendToUser(eq(GUARDIAN_ID), eq("connection-refused"), anyMap());
        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(eq(GUARDIAN_ID), eq(NotificationType.CONNECTION_REFUSED), captor.capture());
        assertThat(captor.getValue().body()).isEqualTo("연결 요청이 거절되었습니다.");
    }

    @Test
    @DisplayName("보호자가 해제 → 피보호자에게 '보호자가 연결을 해제했습니다' 디스패치")
    void handleDisconnected_보호자해제_문구() {
        ConnectionDisconnectedEvent event = new ConnectionDisconnectedEvent(
                CONNECTION_ID, WARD_ID, ConnectionDisconnectedEvent.DisconnectedBy.GUARDIAN);

        listener.handleDisconnected(event);

        verify(webSocketEventPublisher).sendToUser(eq(WARD_ID), eq("connection-cancelled"), anyMap());
        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(eq(WARD_ID), eq(NotificationType.CONNECTION_DISCONNECTED), captor.capture());
        assertThat(captor.getValue().body()).isEqualTo("보호자가 연결을 해제했습니다.");
    }

    @Test
    @DisplayName("피보호자가 해제 → 보호자에게 '피보호자가 연결을 해제했습니다' 디스패치")
    void handleDisconnected_피보호자해제_문구() {
        ConnectionDisconnectedEvent event = new ConnectionDisconnectedEvent(
                CONNECTION_ID, GUARDIAN_ID, ConnectionDisconnectedEvent.DisconnectedBy.WARD);

        listener.handleDisconnected(event);

        verify(webSocketEventPublisher).sendToUser(eq(GUARDIAN_ID), eq("connection-cancelled"), anyMap());
        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(eq(GUARDIAN_ID), eq(NotificationType.CONNECTION_DISCONNECTED), captor.capture());
        assertThat(captor.getValue().body()).isEqualTo("피보호자가 연결을 해제했습니다.");
    }
}

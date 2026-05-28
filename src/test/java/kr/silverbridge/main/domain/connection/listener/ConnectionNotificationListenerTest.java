package kr.silverbridge.main.domain.connection.listener;

import kr.silverbridge.main.domain.connection.event.ConnectionAcceptedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionDisconnectedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionRefusedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionRequestedEvent;
import kr.silverbridge.main.domain.notification.service.FcmService;
import kr.silverbridge.main.global.websocket.WebSocketEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * ConnectionNotificationListener 단위 테스트.
 *
 * AFTER_COMMIT 핸들러를 직접 호출하여 WebSocket + FCM 발송 대상·문구를 검증한다.
 * (FcmService / WebSocketEventPublisher는 각자 내부에서 예외를 삼키므로 리스너는 발송 위임만 담당)
 */
@ExtendWith(MockitoExtension.class)
class ConnectionNotificationListenerTest {

    @Mock private WebSocketEventPublisher webSocketEventPublisher;
    @Mock private FcmService fcmService;

    @InjectMocks private ConnectionNotificationListener listener;

    private static final long CONNECTION_ID = 100L;
    private static final String GUARDIAN_ID = "GD0001";
    private static final String WARD_ID = "WD0001";
    private static final String GUARDIAN_NAME = "박보호";

    @Test
    @DisplayName("연결 요청 이벤트(relation 있음) → 피보호자에게 WS + 관계 포함 FCM 발송")
    void handleRequested_relation있음_관계포함문구() {
        ConnectionRequestedEvent event =
                new ConnectionRequestedEvent(CONNECTION_ID, GUARDIAN_ID, WARD_ID, GUARDIAN_NAME, "아들");

        listener.handleRequested(event);

        verify(webSocketEventPublisher).sendToUser(eq(WARD_ID), eq("connection-request"), anyMap());
        verify(fcmService).sendToUser(
                eq(WARD_ID), eq("연결 요청"),
                eq("아들 박보호님이 연결을 요청했어요."), anyMap());
    }

    @Test
    @DisplayName("연결 요청 이벤트(relation 없음) → fallback 문구로 FCM 발송")
    void handleRequested_relation없음_fallback문구() {
        ConnectionRequestedEvent event =
                new ConnectionRequestedEvent(CONNECTION_ID, GUARDIAN_ID, WARD_ID, GUARDIAN_NAME, null);

        listener.handleRequested(event);

        verify(fcmService).sendToUser(
                eq(WARD_ID), eq("연결 요청"),
                eq("박보호 보호자가 연결을 요청했습니다."), anyMap());
    }

    @Test
    @DisplayName("연결 수락 이벤트 → 보호자에게 WS + 수락 FCM 발송")
    void handleAccepted_보호자에게_수락알림() {
        ConnectionAcceptedEvent event = new ConnectionAcceptedEvent(CONNECTION_ID, GUARDIAN_ID);

        listener.handleAccepted(event);

        verify(webSocketEventPublisher).sendToUser(eq(GUARDIAN_ID), eq("connection-accepted"), anyMap());
        verify(fcmService).sendToUser(
                eq(GUARDIAN_ID), eq("연결 수락"),
                eq("피보호자가 연결 요청을 수락했습니다."), anyMap());
    }

    @Test
    @DisplayName("연결 거절 이벤트 → 보호자에게 WS + '연결 요청이 거절되었습니다' FCM 발송")
    void handleRefused_보호자에게_거절알림() {
        ConnectionRefusedEvent event = new ConnectionRefusedEvent(CONNECTION_ID, GUARDIAN_ID);

        listener.handleRefused(event);

        verify(webSocketEventPublisher).sendToUser(eq(GUARDIAN_ID), eq("connection-refused"), anyMap());
        verify(fcmService).sendToUser(
                eq(GUARDIAN_ID), eq("연결 거절"),
                eq("연결 요청이 거절되었습니다."), anyMap());
    }

    @Test
    @DisplayName("보호자가 해제 → 피보호자에게 '보호자가 연결을 해제했습니다' 알림")
    void handleDisconnected_보호자해제_문구() {
        ConnectionDisconnectedEvent event = new ConnectionDisconnectedEvent(
                CONNECTION_ID, WARD_ID, ConnectionDisconnectedEvent.DisconnectedBy.GUARDIAN);

        listener.handleDisconnected(event);

        verify(webSocketEventPublisher).sendToUser(eq(WARD_ID), eq("connection-cancelled"), anyMap());
        verify(fcmService).sendToUser(
                eq(WARD_ID), eq("연결 해제"),
                eq("보호자가 연결을 해제했습니다."), anyMap());
    }

    @Test
    @DisplayName("피보호자가 해제 → 보호자에게 '피보호자가 연결을 해제했습니다' 알림")
    void handleDisconnected_피보호자해제_문구() {
        ConnectionDisconnectedEvent event = new ConnectionDisconnectedEvent(
                CONNECTION_ID, GUARDIAN_ID, ConnectionDisconnectedEvent.DisconnectedBy.WARD);

        listener.handleDisconnected(event);

        verify(webSocketEventPublisher).sendToUser(eq(GUARDIAN_ID), eq("connection-cancelled"), anyMap());
        verify(fcmService).sendToUser(
                eq(GUARDIAN_ID), eq("연결 해제"),
                eq("피보호자가 연결을 해제했습니다."), anyMap());
    }
}

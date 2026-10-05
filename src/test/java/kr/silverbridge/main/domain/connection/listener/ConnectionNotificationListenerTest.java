package kr.silverbridge.main.domain.connection.listener;

import kr.silverbridge.main.domain.connection.event.ConnectionAcceptedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionDisconnectedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionForcedEvent;
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
    @DisplayName("요청 취소 → 피보호자에게 WS 갱신 신호만 보내고, 푸시·문자·알림 이력(디스패처)은 쓰지 않는다 (QA BE-3, 무알림 정책 유지)")
    void handleRequestCancelled_WS갱신만() {
        listener.handleRequestCancelled(
                new kr.silverbridge.main.domain.connection.event.ConnectionRequestCancelledEvent(CONNECTION_ID, WARD_ID));

        // 기존 connection-cancelled는 FE가 "연결이 해제되었습니다" 토스트로 보여 주므로 쓰지 않는다
        verify(webSocketEventPublisher).sendToUser(eq(WARD_ID), eq("connection-request-cancelled"), anyMap());
        org.mockito.Mockito.verifyNoInteractions(notificationDispatcher);
    }

    @Test
    @DisplayName("연결 요청 이벤트(relation 있음) → 피보호자에게 WS + 관계 포함 알림 디스패치")
    void handleRequested_relation있음_관계포함문구() {
        ConnectionRequestedEvent event =
                new ConnectionRequestedEvent(CONNECTION_ID, GUARDIAN_ID, WARD_ID, GUARDIAN_NAME, "아들");

        listener.handleRequested(event);

        verify(webSocketEventPublisher).sendToUser(eq(WARD_ID), eq("connection-request"), anyMap());

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        // 두 번째 인자 = 관련 피보호자(관리자 알림 이력). 연결 요청은 수신자가 곧 피보호자다
        verify(notificationDispatcher).dispatch(eq(WARD_ID), eq(WARD_ID), eq(NotificationType.CONNECTION_REQUEST), captor.capture());
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
        verify(notificationDispatcher).dispatch(eq(WARD_ID), eq(WARD_ID), eq(NotificationType.CONNECTION_REQUEST), captor.capture());
        assertThat(captor.getValue().body()).isEqualTo("박보호 보호자가 연결을 요청했습니다.");
    }

    @Test
    @DisplayName("연결 수락 이벤트 → 보호자에게 WS + 수락 알림 디스패치")
    void handleAccepted_보호자에게_수락알림() {
        ConnectionAcceptedEvent event = new ConnectionAcceptedEvent(CONNECTION_ID, GUARDIAN_ID, WARD_ID);

        listener.handleAccepted(event);

        // 일반 수락 WS payload는 그대로(connectionId만) - 하위호환 (CONN-G01은 강제 연결만 바꾼다)
        verify(webSocketEventPublisher).sendToUser(eq(GUARDIAN_ID), eq("connection-accepted"),
                eq(java.util.Map.of("connectionId", CONNECTION_ID)));
        // 수신자는 보호자, 두 번째 인자는 이력 표시용 피보호자
        verify(notificationDispatcher).dispatch(
                eq(GUARDIAN_ID), eq(WARD_ID), eq(NotificationType.CONNECTION_ACCEPTED),
                eq(NotificationContent.of("연결 수락", "피보호자가 연결 요청을 수락했습니다.",
                        java.util.Map.of("type", "CONNECTION_ACCEPTED",
                                "connectionId", String.valueOf(CONNECTION_ID)))));
    }

    @Test
    @DisplayName("연결 거절 이벤트 → 보호자에게 WS + '연결 요청이 거절되었습니다' 디스패치")
    void handleRefused_보호자에게_거절알림() {
        ConnectionRefusedEvent event = new ConnectionRefusedEvent(CONNECTION_ID, GUARDIAN_ID, WARD_ID);

        listener.handleRefused(event);

        verify(webSocketEventPublisher).sendToUser(eq(GUARDIAN_ID), eq("connection-refused"), anyMap());
        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(eq(GUARDIAN_ID), eq(WARD_ID), eq(NotificationType.CONNECTION_REFUSED), captor.capture());
        assertThat(captor.getValue().body()).isEqualTo("연결 요청이 거절되었습니다.");
        // FE가 포그라운드에서 문구를 렌더링하는 키. 해제(CONNECTION_CANCELLED)와 절대 섞이면 안 됨.
        assertThat(captor.getValue().data()).containsEntry("type", "CONNECTION_REFUSED");
    }

    @Test
    @DisplayName("보호자가 해제 → 피보호자에게 '보호자가 연결을 해제했습니다' 디스패치")
    void handleDisconnected_보호자해제_문구() {
        ConnectionDisconnectedEvent event = new ConnectionDisconnectedEvent(
                CONNECTION_ID, WARD_ID, ConnectionDisconnectedEvent.DisconnectedBy.GUARDIAN, GUARDIAN_ID, WARD_ID);

        listener.handleDisconnected(event);

        verify(webSocketEventPublisher).sendToUser(eq(WARD_ID), eq("connection-cancelled"), anyMap());
        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(eq(WARD_ID), eq(WARD_ID), eq(NotificationType.CONNECTION_DISCONNECTED), captor.capture());
        assertThat(captor.getValue().body()).isEqualTo("보호자가 연결을 해제했습니다.");
        // 해제 와이어 식별자는 CONNECTION_CANCELLED(레거시 호환). 거절(CONNECTION_REFUSED)과 섞이지 않음을 고정.
        assertThat(captor.getValue().data()).containsEntry("type", "CONNECTION_CANCELLED");
    }

    @Test
    @DisplayName("피보호자가 해제 → 보호자에게 '피보호자가 연결을 해제했습니다' 디스패치")
    void handleDisconnected_피보호자해제_문구() {
        ConnectionDisconnectedEvent event = new ConnectionDisconnectedEvent(
                CONNECTION_ID, GUARDIAN_ID, ConnectionDisconnectedEvent.DisconnectedBy.WARD, GUARDIAN_ID, WARD_ID);

        listener.handleDisconnected(event);

        verify(webSocketEventPublisher).sendToUser(eq(GUARDIAN_ID), eq("connection-cancelled"), anyMap());
        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        // 알림은 보호자에게 가도 이력의 피보호자 칸은 연결의 피보호자다
        verify(notificationDispatcher).dispatch(eq(GUARDIAN_ID), eq(WARD_ID), eq(NotificationType.CONNECTION_DISCONNECTED), captor.capture());
        assertThat(captor.getValue().body()).isEqualTo("피보호자가 연결을 해제했습니다.");
        assertThat(captor.getValue().data()).containsEntry("type", "CONNECTION_CANCELLED");
    }

    @Test
    @DisplayName("보호자 탈퇴 → 피보호자에게 '보호자가 탈퇴해 연결이 종료되었습니다' (M-2)")
    void handleDisconnected_보호자탈퇴_문구() {
        ConnectionDisconnectedEvent event = new ConnectionDisconnectedEvent(
                CONNECTION_ID, WARD_ID, ConnectionDisconnectedEvent.DisconnectedBy.WITHDRAWN, GUARDIAN_ID, WARD_ID);

        listener.handleDisconnected(event);

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(eq(WARD_ID), eq(WARD_ID), eq(NotificationType.CONNECTION_DISCONNECTED), captor.capture());
        // "보호자가 연결을 해제했습니다"로 나가면 남은 쪽은 "나를 끊었다"로 읽는다
        assertThat(captor.getValue().body()).isEqualTo("보호자가 탈퇴해 연결이 종료되었습니다.");
        assertThat(captor.getValue().data()).containsEntry("type", "CONNECTION_CANCELLED");
    }

    @Test
    @DisplayName("피보호자 탈퇴 → 보호자에게 '피보호자가 탈퇴해 연결이 종료되었습니다' (M-2)")
    void handleDisconnected_피보호자탈퇴_문구() {
        ConnectionDisconnectedEvent event = new ConnectionDisconnectedEvent(
                CONNECTION_ID, GUARDIAN_ID, ConnectionDisconnectedEvent.DisconnectedBy.WITHDRAWN, GUARDIAN_ID, WARD_ID);

        listener.handleDisconnected(event);

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(eq(GUARDIAN_ID), eq(WARD_ID), eq(NotificationType.CONNECTION_DISCONNECTED), captor.capture());
        assertThat(captor.getValue().body()).isEqualTo("피보호자가 탈퇴해 연결이 종료되었습니다.");
    }

    @Test
    @DisplayName("연결 요청 relation이 제로폭 문자만 → 빈 접두어 없이 fallback 문구 (CONN-G14, DTO와 같은 판정 기준)")
    void handleRequested_보이지않는_relation은_fallback() {
        listener.handleRequested(
                new ConnectionRequestedEvent(CONNECTION_ID, GUARDIAN_ID, WARD_ID, GUARDIAN_NAME, "\u200B\uFEFF"));

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(eq(WARD_ID), eq(WARD_ID), eq(NotificationType.CONNECTION_REQUEST), captor.capture());
        assertThat(captor.getValue().body()).isEqualTo("박보호 보호자가 연결을 요청했습니다.");
    }

    @Test
    @DisplayName("강제 연결 → 양쪽 WS(connection-accepted)에 FCM과 같은 type=CONNECTION_FORCED·제목·본문을 싣는다 - '수락했습니다' 거짓 문구 방지 (CONN-G01)")
    @SuppressWarnings("unchecked")
    void handleForced_WS에_관리자_연결_문구() {
        listener.handleForced(new ConnectionForcedEvent(CONNECTION_ID, GUARDIAN_ID, WARD_ID, GUARDIAN_NAME, "김피보"));

        ArgumentCaptor<Object> guardianWs = ArgumentCaptor.forClass(Object.class);
        verify(webSocketEventPublisher).sendToUser(eq(GUARDIAN_ID), eq("connection-accepted"), guardianWs.capture());
        assertThat((java.util.Map<String, Object>) guardianWs.getValue())
                .containsEntry("connectionId", CONNECTION_ID)
                .containsEntry("type", "CONNECTION_FORCED")
                .containsEntry("title", "연결 완료")
                .containsEntry("body", "관리자가 김피보님과의 연결을 완료했습니다.");

        ArgumentCaptor<Object> wardWs = ArgumentCaptor.forClass(Object.class);
        verify(webSocketEventPublisher).sendToUser(eq(WARD_ID), eq("connection-accepted"), wardWs.capture());
        assertThat((java.util.Map<String, Object>) wardWs.getValue())
                .containsEntry("connectionId", CONNECTION_ID)
                .containsEntry("type", "CONNECTION_FORCED")
                .containsEntry("title", "보호자 연결")
                .containsEntry("body", "관리자가 박보호님을 보호자로 연결했습니다.");

        // FCM 문구와 WS 문구가 같아야 FE가 같은 알림으로 다룬다
        ArgumentCaptor<NotificationContent> guardianFcm = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(eq(GUARDIAN_ID), eq(WARD_ID), eq(NotificationType.CONNECTION_FORCED), guardianFcm.capture());
        assertThat(guardianFcm.getValue().body()).isEqualTo("관리자가 김피보님과의 연결을 완료했습니다.");
        assertThat(guardianFcm.getValue().data()).containsEntry("type", "CONNECTION_FORCED");
        ArgumentCaptor<NotificationContent> wardFcm = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(eq(WARD_ID), eq(WARD_ID), eq(NotificationType.CONNECTION_FORCED), wardFcm.capture());
        assertThat(wardFcm.getValue().body()).isEqualTo("관리자가 박보호님을 보호자로 연결했습니다.");
    }

    @Test
    @DisplayName("CONN-G02: 보호자 알림 발송이 예외로 끝나도 피보호자 화면 갱신 WS는 이미 나가 있다 - WS는 알림 설정·발송과 무관")
    void handleForced_발송_실패해도_양쪽_WS는_나간다() {
        org.mockito.Mockito.when(notificationDispatcher.dispatch(eq(GUARDIAN_ID), eq(WARD_ID),
                        eq(NotificationType.CONNECTION_FORCED), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("설정 조회 실패"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> listener.handleForced(
                        new ConnectionForcedEvent(CONNECTION_ID, GUARDIAN_ID, WARD_ID, GUARDIAN_NAME, "김피보")))
                .isInstanceOf(IllegalStateException.class);

        verify(webSocketEventPublisher).sendToUser(eq(GUARDIAN_ID), eq("connection-accepted"), anyMap());
        verify(webSocketEventPublisher).sendToUser(eq(WARD_ID), eq("connection-accepted"), anyMap());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "GUARDIAN, WD0001, 보호자가 연결을 해제했습니다.",
            "WARD, GD0001, 피보호자가 연결을 해제했습니다.",
            "ADMIN, GD0001, 관리자가 연결을 해제했습니다.",
            "WITHDRAWN, WD0001, 보호자가 탈퇴해 연결이 종료되었습니다.",
            "WITHDRAWN, GD0001, 피보호자가 탈퇴해 연결이 종료되었습니다."
    })
    @DisplayName("연결 해제 WS(connection-cancelled)에 해제 주체와 FCM과 같은 제목·본문을 싣는다, connectionId 유지 (CONN-G12)")
    @SuppressWarnings("unchecked")
    void handleDisconnected_WS에_주체별_문구(ConnectionDisconnectedEvent.DisconnectedBy by, String target, String expectedBody) {
        listener.handleDisconnected(new ConnectionDisconnectedEvent(CONNECTION_ID, target, by, GUARDIAN_ID, WARD_ID));

        ArgumentCaptor<Object> ws = ArgumentCaptor.forClass(Object.class);
        verify(webSocketEventPublisher).sendToUser(eq(target), eq("connection-cancelled"), ws.capture());
        assertThat((java.util.Map<String, Object>) ws.getValue())
                .containsEntry("connectionId", CONNECTION_ID)
                .containsEntry("type", "CONNECTION_CANCELLED")
                .containsEntry("disconnectedBy", by.name())
                .containsEntry("title", "연결 해제")
                .containsEntry("body", expectedBody);

        ArgumentCaptor<NotificationContent> fcm = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(eq(target), eq(WARD_ID), eq(NotificationType.CONNECTION_DISCONNECTED), fcm.capture());
        assertThat(fcm.getValue().title()).isEqualTo("연결 해제");
        assertThat(fcm.getValue().body()).isEqualTo(expectedBody);
    }
}

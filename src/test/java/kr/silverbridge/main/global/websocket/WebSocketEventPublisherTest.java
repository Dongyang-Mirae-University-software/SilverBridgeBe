package kr.silverbridge.main.global.websocket;

import kr.silverbridge.main.global.enums.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebSocket 수신자 상태 차단(ANOM-G10·ADMIN-G28). 알림 디스패처의 "정지 계정에는 알림을 보내지 않는다"와 같은 기준 -
 * 이용 중이 아니면 막고, 상태를 모르면(행 없음·조회 실패) 막지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class WebSocketEventPublisherTest {

    private static final String USER_ID = "GD0001";

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private WebSocketRecipientStatusPort recipientStatusPort;
    @InjectMocks private WebSocketEventPublisher publisher;

    private final Map<String, Object> payload = Map.of("wardId", "WD0001");

    @Test
    @DisplayName("이용 중(ACTIVE) 수신자에게는 /topic/{userId}/{event}로 발송한다")
    void 이용중_발송() {
        when(recipientStatusPort.findStatus(USER_ID)).thenReturn(Optional.of(Status.ACTIVE));

        publisher.sendToUser(USER_ID, "sos-triggered", payload);

        verify(messagingTemplate).convertAndSend("/topic/GD0001/sos-triggered", (Object) payload);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Status.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("이용 중이 아닌(이용 제한·탈퇴 진행) 수신자에게는 보내지 않는다 - 이미 열린 소켓으로 새어 나가지 않게")
    void 이용중_아니면_차단(Status status) {
        when(recipientStatusPort.findStatus(USER_ID)).thenReturn(Optional.of(status));

        publisher.sendToUser(USER_ID, "anomaly-detected", payload);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("사용자 행이 없으면 상태를 모르는 것이지 정지가 아니므로 발송한다")
    void 상태_모름_발송() {
        when(recipientStatusPort.findStatus(USER_ID)).thenReturn(Optional.empty());

        publisher.sendToUser(USER_ID, "connection-cancelled", payload);

        verify(messagingTemplate).convertAndSend("/topic/GD0001/connection-cancelled", (Object) payload);
    }

    @Test
    @DisplayName("상태 조회가 실패해도 발송을 막지 않고 예외를 밖으로 내보내지 않는다")
    void 상태조회_실패_발송() {
        when(recipientStatusPort.findStatus(USER_ID)).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> publisher.sendToUser(USER_ID, "medication-taken", payload)).doesNotThrowAnyException();

        verify(messagingTemplate).convertAndSend("/topic/GD0001/medication-taken", (Object) payload);
    }

    @Test
    @DisplayName("발송 실패는 흡수한다 - 한 수신자 실패가 호출부의 나머지 수신자를 막지 않는다")
    void 발송_실패_흡수() {
        when(recipientStatusPort.findStatus(USER_ID)).thenReturn(Optional.of(Status.ACTIVE));
        doThrow(new MessagingException("broker down"))
                .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

        assertThatCode(() -> publisher.sendToUser(USER_ID, "sos-triggered", payload)).doesNotThrowAnyException();
    }
}

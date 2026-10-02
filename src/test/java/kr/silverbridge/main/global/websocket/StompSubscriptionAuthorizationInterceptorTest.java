package kr.silverbridge.main.global.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 클라이언트 인바운드 STOMP 프레임 검사 - SUBSCRIBE 본인 토픽 한정 + 클라이언트 SEND로 토픽에 쓰기 금지 (XCUT-G04).
 */
class StompSubscriptionAuthorizationInterceptorTest {

    private static final String ME = "GD0001";
    private static final String OTHER = "WD0001";

    private final StompSubscriptionAuthorizationInterceptor interceptor = new StompSubscriptionAuthorizationInterceptor();
    private final MessageChannel channel = mock(MessageChannel.class);

    private Message<byte[]> frame(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", ME);
        accessor.setSessionAttributes(attrs);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage("{\"type\":\"SOS_TRIGGERED\"}".getBytes(), accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("클라이언트 SEND로 남의 토픽에 발행 → 거부 (가짜 SOS 알림 차단)")
    void 클라이언트SEND_남의토픽_거부() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, "/topic/" + OTHER + "/sos-triggered"), channel))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("클라이언트 SEND로 본인 토픽에 발행해도 거부 - 토픽에 쓰는 것은 서버뿐")
    void 클라이언트SEND_본인토픽도_거부() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, "/topic/" + ME + "/anomaly-detected"), channel))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("브로커 prefix 변형·destination 없음 SEND도 거부 (허용 목록 /app/ 만)")
    void 클라이언트SEND_변형주소_거부() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, "/topic"), channel))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, null), channel))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("앱 prefix(/app/) SEND는 통과")
    void 클라이언트SEND_앱주소_통과() {
        Message<byte[]> message = frame(StompCommand.SEND, "/app/ping");

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    @DisplayName("본인 토픽 SUBSCRIBE는 통과, 남의 토픽 SUBSCRIBE는 거부 (기존 동작 유지)")
    void 구독검사_유지() {
        Message<byte[]> mine = frame(StompCommand.SUBSCRIBE, "/topic/" + ME + "/sos-triggered");
        assertThat(interceptor.preSend(mine, channel)).isSameAs(mine);

        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/" + OTHER + "/sos-triggered"), channel))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("서버 발행 형태의 메시지(STOMP 명령 없는 MESSAGE)는 통과 - SimpMessagingTemplate 발행은 영향 없음")
    void 서버발행메시지_통과() {
        // SimpMessagingTemplate.convertAndSend는 brokerChannel로 가서 이 인터셉터(clientInboundChannel)를 거치지 않는다.
        // 설령 거쳐도 클라이언트 SEND 명령이 아니라 막히지 않음을 함께 확인한다.
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination("/topic/" + OTHER + "/sos-triggered");
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage("{}".getBytes(), accessor.getMessageHeaders());

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }
}

package kr.silverbridge.main.global.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserSessionTrackingHandlerDecoratorTest {

    private final WebSocketHandler delegate = mock(WebSocketHandler.class);
    private final WebSocketSessionRegistry registry = new WebSocketSessionRegistry();
    private final UserSessionTrackingHandlerDecorator decorator = new UserSessionTrackingHandlerDecorator(delegate, registry);

    private WebSocketSession session(String userId) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        if (userId != null) attributes.put("userId", userId);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }

    @Test
    @DisplayName("연결 수립 시 핸드셰이크 attributes의 userId로 등록하고 하위 핸들러에 위임한다")
    void 연결수립_등록() throws Exception {
        WebSocketSession s = session("user-1");

        decorator.afterConnectionEstablished(s);

        assertThat(registry.sessionsOf("user-1")).containsExactly(s);
        verify(delegate).afterConnectionEstablished(s);
    }

    @Test
    @DisplayName("연결 종료 시 목록에서 뺀다 - 하위 핸들러가 예외를 던져도 뺀다")
    void 연결종료_제거() throws Exception {
        WebSocketSession s = session("user-1");
        decorator.afterConnectionEstablished(s);
        doThrow(new IllegalStateException("boom")).when(delegate).afterConnectionClosed(s, CloseStatus.NORMAL);

        assertThatThrownBy(() -> decorator.afterConnectionClosed(s, CloseStatus.NORMAL))
                .isInstanceOf(IllegalStateException.class);
        assertThat(registry.sessionsOf("user-1")).isEmpty();
    }

    @Test
    @DisplayName("userId 속성이 없는 세션은 등록하지 않는다")
    void userId없음_미등록() throws Exception {
        WebSocketSession s = session(null);

        decorator.afterConnectionEstablished(s);

        assertThat(registry.sessionsOf("null")).isEmpty();
        verify(delegate).afterConnectionEstablished(s);
    }
}

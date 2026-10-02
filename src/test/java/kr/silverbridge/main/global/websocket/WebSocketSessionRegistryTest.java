package kr.silverbridge.main.global.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WebSocketSessionRegistryTest {

    private final WebSocketSessionRegistry registry = new WebSocketSessionRegistry();

    @Test
    @DisplayName("한 사용자의 여러 세션(다중 기기·탭)을 모두 담고, 다른 사용자 세션과 섞지 않는다")
    void register_다중세션() {
        WebSocketSession a = mock(WebSocketSession.class);
        WebSocketSession b = mock(WebSocketSession.class);
        WebSocketSession other = mock(WebSocketSession.class);

        registry.register("user-1", a);
        registry.register("user-1", b);
        registry.register("user-2", other);

        assertThat(registry.sessionsOf("user-1")).containsExactlyInAnyOrder(a, b);
        assertThat(registry.sessionsOf("user-2")).containsExactly(other);
    }

    @Test
    @DisplayName("연결 종료 시 그 세션만 빠지고, 마지막 세션이 빠지면 빈 목록이 된다")
    void unregister_제거() {
        WebSocketSession a = mock(WebSocketSession.class);
        WebSocketSession b = mock(WebSocketSession.class);
        registry.register("user-1", a);
        registry.register("user-1", b);

        registry.unregister("user-1", a);
        assertThat(registry.sessionsOf("user-1")).containsExactly(b);

        registry.unregister("user-1", b);
        assertThat(registry.sessionsOf("user-1")).isEmpty();
    }

    @Test
    @DisplayName("userId가 없으면 등록하지 않고, 모르는 사용자 조회는 빈 목록")
    void null_방어() {
        registry.register(null, mock(WebSocketSession.class));
        registry.unregister(null, mock(WebSocketSession.class));
        registry.unregister("ghost", mock(WebSocketSession.class));

        assertThat(registry.sessionsOf(null)).isEmpty();
        assertThat(registry.sessionsOf("ghost")).isEmpty();
    }

    @Test
    @DisplayName("반환 목록은 스냅샷 - 순회 중 연결이 끊겨도 반환된 목록은 바뀌지 않는다")
    void sessionsOf_스냅샷() {
        WebSocketSession a = mock(WebSocketSession.class);
        registry.register("user-1", a);

        var snapshot = registry.sessionsOf("user-1");
        registry.unregister("user-1", a);

        assertThat(snapshot).containsExactly(a);
    }
}

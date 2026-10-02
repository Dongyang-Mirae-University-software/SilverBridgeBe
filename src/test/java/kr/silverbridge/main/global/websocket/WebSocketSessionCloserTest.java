package kr.silverbridge.main.global.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketSessionCloserTest {

    private final WebSocketSessionRegistry registry = new WebSocketSessionRegistry();
    private final WebSocketSessionCloser closer = new WebSocketSessionCloser(registry);

    private WebSocketSession openSession() {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    @DisplayName("그 사용자의 열린 세션을 모두 1008 POLICY_VIOLATION + 고정 reason AUTH_INVALIDATED로 닫는다")
    void closeAll_전체종료() throws Exception {
        WebSocketSession a = openSession();
        WebSocketSession b = openSession();
        WebSocketSession other = mock(WebSocketSession.class);
        registry.register("user-1", a);
        registry.register("user-1", b);
        registry.register("user-2", other);

        int attempted = closer.closeAll("user-1");

        assertThat(attempted).isEqualTo(2);
        CloseStatus expected = new CloseStatus(1008, "AUTH_INVALIDATED");
        verify(a).close(expected);
        verify(b).close(expected);
        verify(other, never()).close(any());
    }

    @Test
    @DisplayName("한 세션 닫기가 실패해도 나머지는 닫고, 예외는 밖으로 새지 않는다")
    void closeAll_실패삼킴() throws Exception {
        WebSocketSession broken = openSession();
        WebSocketSession ok = openSession();
        doThrow(new IOException("socket")).when(broken).close(any());
        registry.register("user-1", broken);
        registry.register("user-1", ok);

        assertThatCode(() -> closer.closeAll("user-1")).doesNotThrowAnyException();
        verify(ok).close(WebSocketSessionCloser.CLOSE_STATUS);
    }

    @Test
    @DisplayName("이미 닫힌 세션은 다시 닫지 않고, 세션이 없으면 아무 일도 하지 않는다")
    void closeAll_닫힌세션_세션없음() throws Exception {
        WebSocketSession closed = mock(WebSocketSession.class);
        when(closed.isOpen()).thenReturn(false);
        registry.register("user-1", closed);

        closer.closeAll("user-1");

        verify(closed, never()).close(any());
        assertThat(closer.closeAll("nobody")).isZero();
        assertThat(closer.closeAll(null)).isZero();
    }
}

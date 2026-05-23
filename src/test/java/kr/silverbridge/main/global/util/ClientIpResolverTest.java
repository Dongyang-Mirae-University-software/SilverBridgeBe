package kr.silverbridge.main.global.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ClientIpResolver} 검증 — X-Real-IP(nginx 세팅, 위조 불가) 우선,
 * 없을 때만 getRemoteAddr() 폴백 (SPOT-H1, 2026-05-23).
 */
@DisplayName("ClientIpResolver")
class ClientIpResolverTest {

    @Test
    @DisplayName("X-Real-IP가 있으면 그 값을 사용한다 (getRemoteAddr 무시 — XFF 스푸핑 우회 차단)")
    void X_Real_IP_우선() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Real-IP")).thenReturn("203.0.113.7");
        // getRemoteAddr()는 framework 전략에서 XFF 선두(스푸핑 가능)값일 수 있음 — 무시되어야 함
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("X-Real-IP 앞뒤 공백은 제거한다")
    void X_Real_IP_트림() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Real-IP")).thenReturn("  203.0.113.7  ");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("X-Real-IP가 없으면(null) getRemoteAddr()로 폴백한다")
    void X_Real_IP_없으면_폴백() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Real-IP")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("X-Real-IP가 빈 문자열이면 getRemoteAddr()로 폴백한다")
    void X_Real_IP_공백이면_폴백() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Real-IP")).thenReturn("   ");
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("10.0.0.5");
    }

    @Test
    @DisplayName("request가 null이면 \"-\"를 반환한다")
    void request_null이면_대체값() {
        assertThat(ClientIpResolver.resolve(null)).isEqualTo("-");
    }
}

package kr.silverbridge.main.global.websocket;

import kr.silverbridge.main.global.jwt.JwtTokenProvider;
import kr.silverbridge.main.global.util.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WS 핸드셰이크 토큰 검증이 HTTP 필터와 동일 수준인지 검증한다 (M-S3-1):
 * typ(refresh 차단) / 로그아웃 블랙리스트 / 비밀번호 변경·탈퇴 무효화.
 */
@ExtendWith(MockitoExtension.class)
class JwtHandshakeInterceptorTest {

    private static final String TOKEN = "valid-token";
    private static final String HASH = "token-hash";
    private static final String USER_ID = "WD0001";

    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private ServerHttpResponse response;
    @Mock private WebSocketHandler wsHandler;

    @InjectMocks private JwtHandshakeInterceptor interceptor;

    private final Map<String, Object> attributes = new HashMap<>();

    @BeforeEach
    void setUp() {
        lenient().when(jwtTokenProvider.validateToken(TOKEN)).thenReturn(true);
        lenient().when(jwtTokenProvider.isAccessToken(TOKEN)).thenReturn(true);
        lenient().when(jwtTokenProvider.hashToken(TOKEN)).thenReturn(HASH);
        lenient().when(jwtTokenProvider.getUserId(TOKEN)).thenReturn(USER_ID);
        lenient().when(redisTemplate.hasKey(RedisKeys.LOGOUT_TOKEN + HASH)).thenReturn(false);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.get(RedisKeys.PASSWORD_INVALIDATE + USER_ID)).thenReturn(null);
    }

    private ServerHttpRequest requestWithToken() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("ws://localhost/ws?token=" + TOKEN));
        return request;
    }

    @Test
    @DisplayName("유효한 access 토큰 → 연결 허용 + 세션에 userId 저장")
    void 유효토큰_연결허용() {
        boolean allowed = interceptor.beforeHandshake(requestWithToken(), response, wsHandler, attributes);

        assertThat(allowed).isTrue();
        assertThat(attributes).containsEntry("userId", USER_ID);
    }

    @Test
    @DisplayName("refresh 토큰으로 연결 시도 → 거부 (HTTP A-H1과 동일 정책)")
    void refresh토큰_거부() {
        when(jwtTokenProvider.isAccessToken(TOKEN)).thenReturn(false);

        boolean allowed = interceptor.beforeHandshake(requestWithToken(), response, wsHandler, attributes);

        assertThat(allowed).isFalse();
        assertThat(attributes).isEmpty();
    }

    @Test
    @DisplayName("로그아웃 블랙리스트 토큰 → 거부")
    void 로그아웃토큰_거부() {
        when(redisTemplate.hasKey(RedisKeys.LOGOUT_TOKEN + HASH)).thenReturn(true);

        boolean allowed = interceptor.beforeHandshake(requestWithToken(), response, wsHandler, attributes);

        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("비밀번호 변경·탈퇴 무효화 시각 이전 발급(iat) 토큰 → 거부")
    void 무효화토큰_거부() {
        when(valueOperations.get(RedisKeys.PASSWORD_INVALIDATE + USER_ID)).thenReturn("2000");
        when(jwtTokenProvider.getIssuedAt(TOKEN)).thenReturn(1000L);

        boolean allowed = interceptor.beforeHandshake(requestWithToken(), response, wsHandler, attributes);

        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("무효화 시각 이후 발급된 새 토큰 → 연결 허용")
    void 무효화이후_새토큰_허용() {
        when(valueOperations.get(RedisKeys.PASSWORD_INVALIDATE + USER_ID)).thenReturn("2000");
        when(jwtTokenProvider.getIssuedAt(TOKEN)).thenReturn(3000L);

        boolean allowed = interceptor.beforeHandshake(requestWithToken(), response, wsHandler, attributes);

        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("token 파라미터 없음 → 거부")
    void 토큰없음_거부() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("ws://localhost/ws"));

        boolean allowed = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(allowed).isFalse();
    }
}

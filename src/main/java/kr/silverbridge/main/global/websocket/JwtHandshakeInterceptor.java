package kr.silverbridge.main.global.websocket;

import kr.silverbridge.main.global.jwt.JwtTokenProvider;
import kr.silverbridge.main.global.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 핸드셰이크 시 JWT 검증
 * 연결 파라미터로 받은 accessToken을 검증하고 userId를 세션에 저장.
 *
 * <p>HTTP의 {@code JwtAuthenticationFilter}와 동일 수준으로 검증한다 (M-S3-1):
 * 서명·만료 → access 토큰 typ(refresh 토큰으로 WS 연결 차단, A-H1) →
 * 로그아웃 블랙리스트 → 비밀번호 변경·탈퇴 무효화(iat 비교). 이 검증이 없으면
 * 무효화된 토큰으로 새 WS 연결을 만들 수 있어 HTTP와 보안 수준이 어긋난다.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String query = request.getURI().getQuery();
        if (query == null) {
            log.warn("WebSocket 연결 거부: 토큰 없음");
            return false;
        }

        // ?token=xxx 파라미터에서 토큰 추출
        String token = null;
        for (String param : query.split("&")) {
            if (param.startsWith("token=")) {
                token = param.substring(6);
                break;
            }
        }

        if (token == null) {
            log.warn("WebSocket 연결 거부: token 파라미터 없음");
            return false;
        }

        try {
            jwtTokenProvider.validateToken(token);

            // refresh 토큰으로 WS 연결 차단 — HTTP 필터의 A-H1과 동일 정책 (M-S3-1)
            if (!jwtTokenProvider.isAccessToken(token)) {
                log.warn("WebSocket 연결 거부: access 토큰이 아님");
                return false;
            }
            // 로그아웃된 토큰 차단 (HTTP 필터와 동일한 블랙리스트 키)
            if (Boolean.TRUE.equals(redisTemplate.hasKey(
                    RedisKeys.LOGOUT_TOKEN + jwtTokenProvider.hashToken(token)))) {
                log.warn("WebSocket 연결 거부: 로그아웃된 토큰");
                return false;
            }

            String userId = jwtTokenProvider.getUserId(token);

            // 비밀번호 변경·탈퇴로 무효화된 토큰 차단 (HTTP 필터의 PASSWORD_INVALIDATE와 동일 비교)
            if (isInvalidated(token, userId)) {
                log.warn("WebSocket 연결 거부: 무효화된 토큰 userId={}", userId);
                return false;
            }

            attributes.put("userId", userId);
            return true;
        } catch (Exception e) {
            log.warn("WebSocket 연결 거부: 유효하지 않은 토큰");
            return false;
        }
    }

    // 무효화 시각(ms) 이전에 발급(iat)된 토큰이면 무효 — JwtAuthenticationFilter.isInvalidatedByPasswordChange와 동일 로직
    private boolean isInvalidated(String token, String userId) {
        String invalidatedAtStr = redisTemplate.opsForValue().get(RedisKeys.PASSWORD_INVALIDATE + userId);
        if (invalidatedAtStr == null) return false;
        try {
            return jwtTokenProvider.getIssuedAt(token) <= Long.parseLong(invalidatedAtStr);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 핸드셰이크 후 처리 불필요
    }
}

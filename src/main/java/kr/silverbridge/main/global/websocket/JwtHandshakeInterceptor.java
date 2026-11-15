package kr.silverbridge.main.global.websocket;

import kr.silverbridge.main.global.jwt.JwtTokenProvider;
import kr.silverbridge.main.global.jwt.TokenInvalidation;
import kr.silverbridge.main.global.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
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
 *
 * <p>Redis 조회 실패·손상된 무효화 값은 연결을 거부하고 503으로 답한다(D1). SOS 발송은 HTTP 경로라
 * WS에는 fail-open 예외가 없다. 예전에는 무효화 값이 숫자가 아니면 "무효화 아님"으로 보고 연결을 허용했다.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    /** 세션 속성: 핸드셰이크에 쓴 access 토큰의 해시(JwtTokenProvider.hashToken). 로그아웃 때 그 토큰의 세션만 닫는다 */
    public static final String ACCESS_TOKEN_HASH_ATTRIBUTE = "accessTokenHash";

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

        String userId;
        try {
            jwtTokenProvider.validateToken(token);

            // refresh 토큰으로 WS 연결 차단 — HTTP 필터의 A-H1과 동일 정책 (M-S3-1)
            if (!jwtTokenProvider.isAccessToken(token)) {
                log.warn("WebSocket 연결 거부: access 토큰이 아님");
                return false;
            }
            userId = jwtTokenProvider.getUserId(token);
        } catch (Exception e) {
            log.warn("WebSocket 연결 거부: 유효하지 않은 토큰");
            return false;
        }

        String tokenHash = jwtTokenProvider.hashToken(token);
        // 로그아웃·무효화 검사는 Redis를 읽는다. 저장소 오류는 토큰 문제가 아니므로 503으로 구분한다 (D1, AUTH-G21)
        try {
            // 로그아웃된 토큰 차단 (HTTP 필터와 동일한 블랙리스트 키)
            if (Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.LOGOUT_TOKEN + tokenHash))) {
                log.warn("WebSocket 연결 거부: 로그아웃된 토큰");
                return false;
            }

            // 비밀번호 변경·탈퇴로 무효화된 토큰 차단 (HTTP 필터의 PASSWORD_INVALIDATE와 동일 비교)
            if (isInvalidated(token, userId)) {
                log.warn("WebSocket 연결 거부: 무효화된 토큰 userId={}", userId);
                return false;
            }
        } catch (TokenInvalidation.CorruptValueException e) {
            // 손상 값 - 조회 오류와 같이 503으로 거부하되 태그를 나눈다(HTTP 필터와 같은 기준, XCUT-G03)
            log.error("[AUTH-STORE-CORRUPT] WebSocket 연결 거부: 인증 저장소 값 손상 userId={} reason={}",
                    userId, e.reason());
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        } catch (RuntimeException e) {
            log.warn("[AUTH-STORE-UNAVAILABLE] WebSocket 연결 거부: 인증 저장소 조회 실패 userId={} error={}",
                    userId, e.getClass().getSimpleName());
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }

        attributes.put("userId", userId);
        // 로그아웃 때 이 토큰으로 연 세션만 골라 닫는 데 쓴다(원문이 아니라 해시) - WebSocketSessionCloser.closeByAccessToken
        attributes.put(ACCESS_TOKEN_HASH_ATTRIBUTE, tokenHash);
        return true;
    }

    // 무효화 시각(초)보다 앞선 초에 발급(iat)된 토큰이면 무효 — JwtAuthenticationFilter와 같은 TokenInvalidation 규칙(D2).
    // 저장값이 손상됐으면 TokenInvalidation.CorruptValueException을 던진다(호출자가 503으로 처리).
    private boolean isInvalidated(String token, String userId) {
        String invalidatedAtStr = redisTemplate.opsForValue().get(RedisKeys.PASSWORD_INVALIDATE + userId);
        if (invalidatedAtStr == null) return false;
        long invalidatedSec = TokenInvalidation.parseEpochSecond(invalidatedAtStr, System.currentTimeMillis());
        return TokenInvalidation.isRevoked(jwtTokenProvider.getIssuedAt(token), invalidatedSec);
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 핸드셰이크 후 처리 불필요
    }
}

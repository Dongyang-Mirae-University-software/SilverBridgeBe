package kr.silverbridge.main.global.websocket;

import kr.silverbridge.main.global.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 핸드셰이크 시 JWT 검증
 * 연결 파라미터로 받은 accessToken을 검증하고 userId를 세션에 저장
 */
@Slf4j
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtTokenProvider jwtTokenProvider;

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
            String userId = jwtTokenProvider.getUserId(token);
            attributes.put("userId", userId);
            return true;
        } catch (Exception e) {
            log.warn("WebSocket 연결 거부: 유효하지 않은 토큰");
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 핸드셰이크 후 처리 불필요
    }
}

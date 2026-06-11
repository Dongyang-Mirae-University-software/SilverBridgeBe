package kr.silverbridge.main.global.config;

import kr.silverbridge.main.global.jwt.JwtTokenProvider;
import kr.silverbridge.main.global.websocket.JwtHandshakeInterceptor;
import kr.silverbridge.main.global.websocket.StompSubscriptionAuthorizationInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;
    private final StompSubscriptionAuthorizationInterceptor subscriptionAuthInterceptor;

    // HTTP CORS(app.cors.allowed-origins)와 동일한 출처 목록 사용 — WS만 와일드카드였던 비대칭 해소 (L-S3-3)
    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 클라이언트 구독 prefix — /topic/{userId}/...
        registry.enableSimpleBroker("/topic");
        // 서버로 메시지 보낼 때 prefix — /app/...
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // WebSocket 연결 엔드포인트. 웹 전용 운영으로 SockJS 폴백 제거 — 클라이언트는 wss://.../ws 직접 연결.
        registry.addEndpoint("/ws")
                .addInterceptors(new JwtHandshakeInterceptor(jwtTokenProvider, redisTemplate))
                .setAllowedOriginPatterns(Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toArray(String[]::new));
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // SUBSCRIBE 시 /topic/{userId}/... 의 userId가 세션 userId와 일치하는지 검증
        registration.interceptors(subscriptionAuthInterceptor);
    }
}

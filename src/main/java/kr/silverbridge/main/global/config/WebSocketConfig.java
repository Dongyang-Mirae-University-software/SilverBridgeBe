package kr.silverbridge.main.global.config;

import kr.silverbridge.main.global.jwt.JwtTokenProvider;
import kr.silverbridge.main.global.websocket.JwtHandshakeInterceptor;
import kr.silverbridge.main.global.websocket.StompSubscriptionAuthorizationInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtTokenProvider jwtTokenProvider;
    private final StompSubscriptionAuthorizationInterceptor subscriptionAuthInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 클라이언트 구독 prefix — /topic/{userId}/...
        registry.enableSimpleBroker("/topic");
        // 서버로 메시지 보낼 때 prefix — /app/...
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // WebSocket 연결 엔드포인트 (SockJS fallback 포함)
        registry.addEndpoint("/ws")
                .addInterceptors(new JwtHandshakeInterceptor(jwtTokenProvider))
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // SUBSCRIBE 시 /topic/{userId}/... 의 userId가 세션 userId와 일치하는지 검증
        registration.interceptors(subscriptionAuthInterceptor);
    }
}

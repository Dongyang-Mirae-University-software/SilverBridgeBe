package kr.silverbridge.main.domain.camera.upload;

import kr.silverbridge.main.domain.camera.config.CameraUploadProperties;
import kr.silverbridge.main.global.websocket.UserSessionTrackingHandlerDecorator;
import kr.silverbridge.main.global.websocket.WebSocketSessionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.web.socket.server.support.OriginHandshakeInterceptor;

import java.util.Arrays;

/**
 * 송출 WebSocket({@code /ws/camera-upload}) 등록. 기존 STOMP 엔드포인트({@code /ws})와 별개의 일반 WebSocket이다 -
 * STOMP의 JWT 핸드셰이크·구독 인가 인터셉터를 공유하지 않고 자체 티켓 인증을 쓴다.
 *
 * <p>{@code /ws/**}는 이미 permitAll이라 SecurityConfig 매처를 추가하지 않는다(인증은 핸드셰이크의 티켓). 출처는
 * HTTP CORS와 같은 목록({@code app.cors.allowed-origins})이며 Origin 헤더가 없는 요청은 통과한다.</p>
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class CameraUploadWebSocketConfig implements WebSocketConfigurer {

    static final String PATH = "/ws/camera-upload";

    private final CameraUploadHandler handler;
    private final CameraUploadHandshakeInterceptor interceptor;
    private final WebSocketSessionRegistry sessionRegistry;
    private final CameraUploadProperties properties;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        // Origin 검사를 티켓 소비·AI 연결보다 앞에 둔다. 등록 API가 자동으로 붙이는 검사는 사용자 인터셉터 뒤라서,
        // 그대로 두면 허용되지 않은 출처의 요청이 티켓을 태우고 AI 연결까지 맺은 뒤에야 거절된다.
        OriginHandshakeInterceptor originCheck = new OriginHandshakeInterceptor();
        originCheck.setAllowedOriginPatterns(Arrays.asList(origins));
        // 세션 추적 데코레이터: 토큰 무효화(정지·비밀번호 변경·역할 변경·탈퇴) 때 서버가 이 연결도 즉시 닫는다(1008 AUTH_INVALIDATED)
        registry.addHandler(new UserSessionTrackingHandlerDecorator(handler, sessionRegistry), PATH)
                .addInterceptors(originCheck, interceptor)
                .setAllowedOriginPatterns(origins);
    }

    /** 컨테이너 기본 바이너리 버퍼(8KB)로는 키프레임이 1009로 거절된다. 바이너리만 올리고 텍스트는 건드리지 않는다. */
    @Bean
    public ServletServerContainerFactoryBean cameraUploadContainerFactory() {
        ServletServerContainerFactoryBean factory = new ServletServerContainerFactoryBean();
        factory.setMaxBinaryMessageBufferSize(properties.getMaxMessageBytes() + 1024);
        return factory;
    }
}

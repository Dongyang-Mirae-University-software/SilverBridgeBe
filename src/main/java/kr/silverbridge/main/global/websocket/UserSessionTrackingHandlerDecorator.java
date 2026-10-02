package kr.silverbridge.main.global.websocket;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * 연결 수립·종료 때 세션을 {@link WebSocketSessionRegistry}에 넣고 뺀다(AUTH-G19·ADMIN-G28).
 * {@code WebSocketConfig.configureWebSocketTransport}의 데코레이터로 STOMP 핸들러를 감싼다.
 *
 * <p>userId는 핸드셰이크 attributes에서 읽는다({@link JwtHandshakeInterceptor}가 검증 후 넣은 값).
 * 값이 없으면 등록하지 않는다 - 인터셉터를 통과하지 못한 연결은 애초에 여기까지 오지 않는다.</p>
 */
public class UserSessionTrackingHandlerDecorator extends WebSocketHandlerDecorator {

    static final String USER_ID_ATTRIBUTE = "userId";

    private final WebSocketSessionRegistry registry;

    public UserSessionTrackingHandlerDecorator(WebSocketHandler delegate, WebSocketSessionRegistry registry) {
        super(delegate);
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        registry.register(userId(session), session);
        super.afterConnectionEstablished(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        // 하위 핸들러가 예외를 던져도 목록에서는 반드시 뺀다 - 닫힌 세션이 남으면 다음 닫기 때 헛돈다
        try {
            super.afterConnectionClosed(session, closeStatus);
        } finally {
            registry.unregister(userId(session), session);
        }
    }

    private static String userId(WebSocketSession session) {
        Object userId = session.getAttributes().get(USER_ID_ATTRIBUTE);
        return userId != null ? userId.toString() : null;
    }
}

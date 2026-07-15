package kr.silverbridge.main.global.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;

/**
 * WebSocket 세션 연결/해제 이벤트 리스너.
 *
 * <p>⚠️ {@code getSessionAttributes()}는 <b>null일 수 있다</b> — CONNECT_ACK 메시지에는 세션 속성이 실려오지
 * 않는다. 이를 방어하지 않아 연결·해제마다 NPE가 났고(로그가 통째로 유실되고 ERROR 스택만 쌓임), 2026-07-14
 * 점검에서 발견해 null-safe 조회로 고쳤다. {@code StompSubscriptionAuthorizationInterceptor}도 같은 방식이다.</p>
 */
@Slf4j
@Component
public class StompEventListener {

    @EventListener
    public void handleConnect(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        log.info("WebSocket 연결: userId={}, sessionId={}", userId(accessor), accessor.getSessionId());
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        log.info("WebSocket 해제: userId={}, sessionId={}", userId(accessor), accessor.getSessionId());
    }

    private String userId(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes == null) {
            return "unknown";
        }
        Object userId = attributes.get("userId");
        return userId != null ? userId.toString() : "unknown";
    }
}

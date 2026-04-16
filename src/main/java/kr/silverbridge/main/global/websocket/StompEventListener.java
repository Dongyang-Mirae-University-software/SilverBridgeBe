package kr.silverbridge.main.global.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * WebSocket 세션 연결/해제 이벤트 리스너
 */
@Slf4j
@Component
public class StompEventListener {

    @EventListener
    public void handleConnect(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String userId = (String) accessor.getSessionAttributes().get("userId");
        log.info("WebSocket 연결: userId={}, sessionId={}", userId, accessor.getSessionId());
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String userId = (String) accessor.getSessionAttributes().get("userId");
        log.info("WebSocket 해제: userId={}, sessionId={}", userId, accessor.getSessionId());
    }
}

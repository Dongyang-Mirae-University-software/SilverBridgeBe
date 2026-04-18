package kr.silverbridge.main.global.websocket;

import kr.silverbridge.main.global.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;
import java.util.concurrent.TimeUnit;


@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketPresenceListener {

    // 접속 상태 유지 시간 — 비정상 종료 시 자동 만료 안전망
    private static final long PRESENCE_TTL_HOURS = 12L;

    private final StringRedisTemplate redisTemplate;

    @EventListener
    public void handleSessionConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String userId = getUserId(accessor);
        if (userId == null) return;

        redisTemplate.opsForValue().set(
                RedisKeys.WS_CONNECTED + userId,
                "1",
                PRESENCE_TTL_HOURS, TimeUnit.HOURS
        );
        log.debug("WebSocket 접속: userId={}", userId);
    }

    @EventListener
    public void handleSessionDisconnected(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String userId = getUserId(accessor);
        if (userId == null) return;

        redisTemplate.delete(RedisKeys.WS_CONNECTED + userId);
        log.debug("WebSocket 접속 해제: userId={}", userId);
    }

    private String getUserId(StompHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) return null;
        return (String) sessionAttributes.get("userId");
    }
}

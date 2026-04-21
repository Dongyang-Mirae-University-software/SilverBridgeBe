package kr.silverbridge.main.global.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * STOMP SUBSCRIBE 프레임의 destination 권한 검증 인터셉터
 *
 * 핸드셰이크 시 JWT 검증만으로는 `/topic/{userId}/...` 구독을 막지 못한다.
 * 인증된 사용자가 타인의 userId가 포함된 토픽을 구독하면 SOS 알림, 표정 이벤트,
 * 연결 알림 등 실시간 데이터가 도청 가능하기 때문에 SUBSCRIBE 시점에 다시 검증한다.
 *
 * 정책: destination 형식이 `/topic/{userId}/...` 일 때, `{userId}`는 세션에 저장된
 * 현재 인증 사용자 ID와 일치해야 한다. 다른 prefix(`/app/...` 등)는 여기서 관여하지 않는다.
 */
@Slf4j
@Component
public class StompSubscriptionAuthorizationInterceptor implements ChannelInterceptor {

    private static final String TOPIC_PREFIX = "/topic/";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            String destination = accessor.getDestination();
            if (destination == null || !destination.startsWith(TOPIC_PREFIX)) {
                return message;
            }

            String sessionUserId = extractSessionUserId(accessor);
            String targetUserId  = extractTopicUserId(destination);

            if (sessionUserId == null || targetUserId == null) {
                log.warn("WebSocket 구독 거부: 세션 또는 대상 userId 파싱 실패 destination={}", destination);
                throw new IllegalStateException("구독 권한이 없습니다.");
            }

            if (!sessionUserId.equals(targetUserId)) {
                log.warn("WebSocket 구독 거부(IDOR 시도): session={} → target={}", sessionUserId, targetUserId);
                throw new IllegalStateException("본인의 토픽만 구독할 수 있습니다.");
            }
        }

        return message;
    }

    // /topic/{userId}/eventType → userId 추출
    private String extractTopicUserId(String destination) {
        String tail = destination.substring(TOPIC_PREFIX.length());
        int slash = tail.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        return tail.substring(0, slash);
    }

    private String extractSessionUserId(StompHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) return null;
        Object userId = sessionAttributes.get("userId");
        return userId != null ? userId.toString() : null;
    }
}

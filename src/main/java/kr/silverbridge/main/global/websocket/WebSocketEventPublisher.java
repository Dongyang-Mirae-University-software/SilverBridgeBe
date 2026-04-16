package kr.silverbridge.main.global.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * WebSocket 실시간 이벤트 발행 컴포넌트
 * 특정 사용자의 토픽으로 메시지 전송
 * 구독 주소: /topic/{userId}/event-type
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    // 특정 사용자에게 WebSocket 메시지 발송
    // destination: /topic/{userId}/{event}
    public void sendToUser(String userId, String event, Object payload) {
        String destination = "/topic/" + userId + "/" + event;
        try {
            messagingTemplate.convertAndSend(destination, payload);
            log.debug("WebSocket 발송: destination={}", destination);
        } catch (Exception e) {
            log.warn("WebSocket 발송 실패: destination={}, error={}", destination, e.getMessage());
        }
    }
}
package kr.silverbridge.main.global.websocket;

import kr.silverbridge.main.global.enums.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * WebSocket 실시간 이벤트 발행 컴포넌트
 * 특정 사용자의 토픽으로 메시지 전송
 * 구독 주소: /topic/{userId}/event-type
 *
 * <p><b>정지 계정에는 보내지 않는다</b>(ANOM-G10·ADMIN-G28). 알림 디스패처와 같은 기준이다 -
 * 수신자가 이용 중(ACTIVE)이 아니면(이용 제한·탈퇴 진행) 발송하지 않고, 사용자 행이 없거나
 * 상태 조회가 실패하면 "알 수 없음"이라 막지 않는다. 정지 시점에 이미 열려 있던 소켓으로
 * SOS·화재 같은 생활 상황이 계속 흘러가던 경로를 여기서 막는다(세션 자체는 끊지 못한다).
 * 막는 기준은 수신자다 - 정지된 사람이 원인인 이벤트는 다른 수신자에게 그대로 간다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketRecipientStatusPort recipientStatusPort;

    // 특정 사용자에게 WebSocket 메시지 발송
    // destination: /topic/{userId}/{event}
    public void sendToUser(String userId, String event, Object payload) {
        if (isBlocked(userId)) {
            log.info("[WS-BLOCKED] 이용 중이 아닌 계정 - 실시간 이벤트 미발송: userId={}", userId);
            return;
        }
        String destination = "/topic/" + userId + "/" + event;
        try {
            messagingTemplate.convertAndSend(destination, payload);
            log.debug("WebSocket 발송: destination={}", destination);
        } catch (Exception e) {
            log.warn("WebSocket 발송 실패: destination={}, error={}", destination, e.getMessage());
        }
    }

    // 상태를 모르면(행 없음·조회 실패) 막지 않는다 - 실시간 알림은 best-effort이고, 모르는 것은 정지가 아니다.
    private boolean isBlocked(String userId) {
        try {
            Optional<Status> status = recipientStatusPort.findStatus(userId);
            return status.isPresent() && status.get() != Status.ACTIVE;
        } catch (Exception e) {
            log.warn("WebSocket 수신자 상태 조회 실패 - 발송 진행: userId={}, error={}",
                    userId, e.getClass().getSimpleName());
            return false;
        }
    }
}

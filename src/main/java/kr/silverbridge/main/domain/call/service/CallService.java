package kr.silverbridge.main.domain.call.service;

import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.notification.service.FcmService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CallService {

    private final ConnectionRepository connectionRepository;
    private final UserRepository userRepository;
    private final FcmService fcmService;
    private final WebSocketEventPublisher webSocketEventPublisher;

    // ─── 피보호자: SOS 긴급통화 ──────────────────────────────────

    // SOS 발신: 연결된 전체 보호자에게 동시 FCM 알림 + WebSocket 알림
    // WebRTC offer는 프론트가 직접 1순위 보호자에게 전송 (signaling API 통해)
    @Transactional(readOnly = true)
    public void triggerSos(String wardId) {
        List<Connection> guardians = connectionRepository
                .findByWardIdAndStatusOrderByPriorityAsc(wardId, ConnectionStatus.ACTIVE);

        if (guardians.isEmpty()) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_FOUND);
        }

        User ward = userRepository.findById(wardId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 전체 보호자에게 동시 알림 (FCM)
        List<String> guardianIds = guardians.stream().map(Connection::getGuardianId).toList();
        fcmService.sendToUsers(guardianIds, "긴급 통화 요청",
                ward.getName() + " 님이 긴급 통화를 요청합니다.",
                Map.of("type", "SOS_CALL", "wardId", wardId, "wardName", ward.getName()));

        // WebSocket으로도 즉시 알림 (앱이 켜져 있을 경우 빠른 반응)
        // priority 순서와 함께 전송해 프론트가 1순위부터 WebRTC offer 시도 가능
        for (int i = 0; i < guardians.size(); i++) {
            Connection conn = guardians.get(i);
            webSocketEventPublisher.sendToUser(conn.getGuardianId(), "sos-call",
                    Map.of(
                            "wardId", wardId,
                            "wardName", ward.getName(),
                            "priority", conn.getPriority(),
                            "connectionId", conn.getId()
                    ));
        }

        log.info("SOS 긴급통화 발신: wardId={}, 보호자 수={}", wardId, guardians.size());
    }

    // ─── WebRTC 시그널링 릴레이 ──────────────────────────────────

    // offer / answer / ice-candidate 메시지를 상대방에게 중계
    // 발신자가 보호자 또는 피보호자인지 검증 후 상대방 topic으로 전달
    @Transactional(readOnly = true)
    public void relaySignal(String senderId, String targetId, String type, Object data) {
        // 두 사용자가 ACTIVE 연결 관계인지 검증
        validateConnected(senderId, targetId);

        webSocketEventPublisher.sendToUser(targetId, "webrtc-signal",
                Map.of("from", senderId, "type", type, "data", data));

        log.debug("WebRTC 시그널 중계: {} → {}, type={}", senderId, targetId, type);
    }

    // 통화 종료 알림
    @Transactional(readOnly = true)
    public void endCall(String senderId, String targetId) {
        validateConnected(senderId, targetId);
        webSocketEventPublisher.sendToUser(targetId, "call-ended",
                Map.of("from", senderId));
        log.info("통화 종료: {} → {}", senderId, targetId);
    }

    // ─── 내부 헬퍼 ───────────────────────────────────────────────

    // 두 사용자가 ACTIVE 연결 관계인지 확인
    private void validateConnected(String userA, String userB) {
        boolean connected = connectionRepository.existsByGuardianIdAndWardIdAndStatusNot(userA, userB, ConnectionStatus.CANCELLED)
                || connectionRepository.existsByGuardianIdAndWardIdAndStatusNot(userB, userA, ConnectionStatus.CANCELLED);
        if (!connected) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_FOUND);
        }
    }
}

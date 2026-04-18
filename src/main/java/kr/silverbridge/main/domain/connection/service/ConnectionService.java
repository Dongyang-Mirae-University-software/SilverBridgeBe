package kr.silverbridge.main.domain.connection.service;

import kr.silverbridge.main.domain.connection.dto.ConnectionRequestDto;
import kr.silverbridge.main.domain.connection.dto.ConnectionResponse;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.notification.service.FcmService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectionService {

    private final ConnectionRepository connectionRepository;
    private final UserRepository userRepository;
    private final FcmService fcmService;
    private final WebSocketEventPublisher webSocketEventPublisher;

    // ─── 보호자 API ──────────────────────────────────────────────

    // 보호자: 내 피보호자 목록 조회 (ACTIVE + PENDING)
    @Transactional(readOnly = true)
    public Page<ConnectionResponse> getMyWards(String guardianId, Pageable pageable) {
        Page<Connection> connections = connectionRepository
                .findByGuardianIdAndStatusIn(guardianId,
                        List.of(ConnectionStatus.ACTIVE, ConnectionStatus.PENDING), pageable);
        return buildResponseFromGuardianView(connections);
    }

    // 보호자: 피보호자에게 페어링 요청 (보호자 → 피보호자)
    @Transactional
    public void requestConnectionAsGuardian(String guardianId, ConnectionRequestDto request) {
        String wardId = request.getTargetId();
        validateConnectionRequest(guardianId, wardId, Role.GUARDIAN, Role.WARD);

        Connection connection = Connection.builder()
                .guardianId(guardianId)
                .wardId(wardId)
                .status(ConnectionStatus.PENDING)
                .initiatedBy(guardianId)
                .priority(1)
                .build();
        connectionRepository.save(connection);

        // 피보호자에게 실시간 알림 (WebSocket)
        webSocketEventPublisher.sendToUser(wardId, "connection-request",
                Map.of("connectionId", connection.getId(), "from", guardianId));

        // 피보호자에게 FCM 푸시 알림
        User guardian = requireUser(guardianId);
        fcmService.sendToUser(wardId, "연결 요청",
                guardian.getName() + " 보호자가 연결을 요청했습니다.",
                Map.of("type", "CONNECTION_REQUEST", "connectionId", String.valueOf(connection.getId())));
    }

    // 보호자: 페어링 요청 거절 또는 연결 해제
    @Transactional
    public void cancelConnectionAsGuardian(String guardianId, Long connectionId) {
        Connection connection = getConnectionForGuardian(guardianId, connectionId);
        if (connection.getStatus() == ConnectionStatus.CANCELLED) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_ACTIVE);
        }
        String wardId = connection.getWardId();
        boolean wasActive = connection.getStatus() == ConnectionStatus.ACTIVE;
        connection.cancel();

        webSocketEventPublisher.sendToUser(wardId, "connection-cancelled",
                Map.of("connectionId", connectionId));
        if (wasActive) {
            fcmService.sendToUser(wardId, "연결 해제",
                    "보호자가 연결을 해제했습니다.",
                    Map.of("type", "CONNECTION_CANCELLED", "connectionId", String.valueOf(connectionId)));
        }
    }

    // ─── 피보호자 API ─────────────────────────────────────────────

    // 피보호자: 내 보호자 목록 조회 (ACTIVE만, 우선순위 순)
    @Transactional(readOnly = true)
    public Page<ConnectionResponse> getMyGuardians(String wardId, Pageable pageable) {
        Page<Connection> connections = connectionRepository
                .findByWardIdAndStatusOrderByPriorityAsc(wardId, ConnectionStatus.ACTIVE, pageable);
        return buildResponseFromWardView(connections);
    }

    // 피보호자: 페어링 요청 수락 (보호자가 보낸 요청)
    @Transactional
    public void acceptConnectionAsWard(String wardId, Long connectionId) {
        Connection connection = getConnectionForWard(wardId, connectionId);
        if (connection.getStatus() != ConnectionStatus.PENDING) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_ACTIVE);
        }
        if (wardId.equals(connection.getInitiatedBy())) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_AUTHORIZED);
        }
        connection.activate();

        webSocketEventPublisher.sendToUser(connection.getGuardianId(), "connection-accepted",
                Map.of("connectionId", connectionId));
        fcmService.sendToUser(connection.getGuardianId(), "연결 수락",
                "피보호자가 연결 요청을 수락했습니다.",
                Map.of("type", "CONNECTION_ACCEPTED", "connectionId", String.valueOf(connectionId)));
    }

    // 피보호자: 요청 거절 또는 연결 해제
    @Transactional
    public void cancelConnectionAsWard(String wardId, Long connectionId) {
        Connection connection = getConnectionForWard(wardId, connectionId);
        if (connection.getStatus() == ConnectionStatus.CANCELLED) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_ACTIVE);
        }
        String guardianId = connection.getGuardianId();
        boolean wasActive = connection.getStatus() == ConnectionStatus.ACTIVE;
        connection.cancel();

        webSocketEventPublisher.sendToUser(guardianId, "connection-cancelled",
                Map.of("connectionId", connectionId));
        if (wasActive) {
            fcmService.sendToUser(guardianId, "연결 해제",
                    "피보호자가 연결을 해제했습니다.",
                    Map.of("type", "CONNECTION_CANCELLED", "connectionId", String.valueOf(connectionId)));
        }
    }

    // 피보호자: 보호자 통화 우선순위 변경
    @Transactional
    public void updatePriority(String wardId, Long connectionId, int priority) {
        Connection connection = getConnectionForWard(wardId, connectionId);
        if (connection.getStatus() != ConnectionStatus.ACTIVE) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_ACTIVE);
        }
        connection.updatePriority(priority);
    }

    // ─── 공통 조회 API ────────────────────────────────────────────

    // 연결 단건 조회 (보호자/피보호자 모두 조회 가능)
    @Transactional(readOnly = true)
    public ConnectionResponse getConnection(String userId, Long connectionId) {
        Connection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new CustomException(ErrorCode.CONNECTION_NOT_FOUND));

        if (connection.getGuardianId().equals(userId)) {
            User ward = requireUser(connection.getWardId());
            return ConnectionResponse.fromGuardianView(connection, ward);
        } else if (connection.getWardId().equals(userId)) {
            User guardian = requireUser(connection.getGuardianId());
            return ConnectionResponse.fromWardView(connection, guardian);
        } else {
            throw new CustomException(ErrorCode.CONNECTION_NOT_AUTHORIZED);
        }
    }

    // ─── 내부 헬퍼 ────────────────────────────────────────────────

    private void validateConnectionRequest(String requesterId, String targetId,
                                           Role requesterRole, Role targetRole) {
        if (requesterId.equals(targetId)) {
            throw new CustomException(ErrorCode.CANNOT_CONNECT_SELF);
        }

        User requester = requireUser(requesterId);
        if (requester.getRole() != requesterRole) {
            throw new CustomException(ErrorCode.INVALID_CONNECTION_ROLE);
        }

        User target = requireUser(targetId);
        if (target.getRole() != targetRole) {
            throw new CustomException(ErrorCode.INVALID_CONNECTION_ROLE);
        }

        String guardianId = (requesterRole == Role.GUARDIAN) ? requesterId : targetId;
        String wardId     = (requesterRole == Role.WARD)     ? requesterId : targetId;

        if (connectionRepository.existsByGuardianIdAndWardIdAndStatusNot(
                guardianId, wardId, ConnectionStatus.CANCELLED)) {
            throw new CustomException(ErrorCode.CONNECTION_ALREADY_EXISTS);
        }
    }

    private Connection getConnectionForGuardian(String guardianId, Long connectionId) {
        Connection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new CustomException(ErrorCode.CONNECTION_NOT_FOUND));
        if (!connection.getGuardianId().equals(guardianId)) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_AUTHORIZED);
        }
        return connection;
    }

    private Connection getConnectionForWard(String wardId, Long connectionId) {
        Connection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new CustomException(ErrorCode.CONNECTION_NOT_FOUND));
        if (!connection.getWardId().equals(wardId)) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_AUTHORIZED);
        }
        return connection;
    }

    private Page<ConnectionResponse> buildResponseFromGuardianView(Page<Connection> connections) {
        List<String> wardIds = connections.map(Connection::getWardId).toList();
        Map<String, User> wardMap = userRepository.findAllById(wardIds).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));
        return connections.map(c -> ConnectionResponse.fromGuardianView(c, wardMap.get(c.getWardId())));
    }

    private Page<ConnectionResponse> buildResponseFromWardView(Page<Connection> connections) {
        List<String> guardianIds = connections.map(Connection::getGuardianId).toList();
        Map<String, User> guardianMap = userRepository.findAllById(guardianIds).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));
        return connections.map(c -> ConnectionResponse.fromWardView(c, guardianMap.get(c.getGuardianId())));
    }

    private User requireUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }
}
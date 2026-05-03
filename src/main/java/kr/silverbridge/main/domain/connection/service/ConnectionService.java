package kr.silverbridge.main.domain.connection.service;

import kr.silverbridge.main.domain.connection.dto.ConnectionRequestDto;
import kr.silverbridge.main.domain.connection.dto.ConnectionResponse;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.event.ConnectionAcceptedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionDisconnectedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionRequestedEvent;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;

    // ─── 보호자 API ──────────────────────────────────────────────

    // 보호자: 내 피보호자 목록 조회 (ACTIVE + PENDING, 최신 요청순)
    @Transactional(readOnly = true)
    public List<ConnectionResponse> getMyWards(String guardianId) {
        List<Connection> connections = connectionRepository
                .findByGuardianIdAndStatusInOrderByCreatedAtDesc(guardianId,
                        List.of(ConnectionStatus.ACTIVE, ConnectionStatus.PENDING));
        return buildResponseFromGuardianView(connections);
    }

    // 보호자: 피보호자에게 페어링 요청 (보호자 → 피보호자)
    // 알림은 ConnectionNotificationListener가 커밋 후 발송
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

        User guardian = requireUser(guardianId);
        eventPublisher.publishEvent(new ConnectionRequestedEvent(
                connection.getId(), guardianId, wardId, guardian.getName()
        ));
    }

    // 보호자: 페어링 요청 취소 (PENDING만)
    @Transactional
    public void cancelPendingAsGuardian(String guardianId, Long connectionId) {
        Connection connection = getConnectionForGuardian(guardianId, connectionId);
        if (connection.getStatus() != ConnectionStatus.PENDING) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_PENDING);
        }
        connection.cancel();
    }

    // 보호자: 연결 해제 (ACTIVE만)
    // 알림은 ConnectionNotificationListener가 커밋 후 발송
    @Transactional
    public void disconnectAsGuardian(String guardianId, Long connectionId) {
        Connection connection = getConnectionForGuardian(guardianId, connectionId);
        if (connection.getStatus() != ConnectionStatus.ACTIVE) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_ACTIVE);
        }
        String wardId = connection.getWardId();
        connection.cancel();

        eventPublisher.publishEvent(new ConnectionDisconnectedEvent(
                connectionId, wardId, ConnectionDisconnectedEvent.DisconnectedBy.GUARDIAN
        ));
    }

    // ─── 피보호자 API ─────────────────────────────────────────────

    // 피보호자: 내 보호자 목록 조회 (ACTIVE만, 우선순위 순)
    @Transactional(readOnly = true)
    public List<ConnectionResponse> getMyGuardians(String wardId) {
        List<Connection> connections = connectionRepository
                .findByWardIdAndStatusOrderByPriorityAsc(wardId, ConnectionStatus.ACTIVE);
        return buildResponseFromWardView(connections);
    }

    // 피보호자: 페어링 요청 수락 (보호자가 보낸 요청)
    // 알림은 ConnectionNotificationListener가 커밋 후 발송
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

        eventPublisher.publishEvent(new ConnectionAcceptedEvent(
                connectionId, connection.getGuardianId()
        ));
    }

    // 피보호자: 보호자 요청 거절 (PENDING만)
    @Transactional
    public void refuseConnectionAsWard(String wardId, Long connectionId) {
        Connection connection = getConnectionForWard(wardId, connectionId);
        if (connection.getStatus() != ConnectionStatus.PENDING) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_PENDING);
        }
        connection.cancel();
    }

    // 피보호자: 연결 해제 (ACTIVE만)
    // 알림은 ConnectionNotificationListener가 커밋 후 발송
    @Transactional
    public void disconnectAsWard(String wardId, Long connectionId) {
        Connection connection = getConnectionForWard(wardId, connectionId);
        if (connection.getStatus() != ConnectionStatus.ACTIVE) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_ACTIVE);
        }
        String guardianId = connection.getGuardianId();
        connection.cancel();

        eventPublisher.publishEvent(new ConnectionDisconnectedEvent(
                connectionId, guardianId, ConnectionDisconnectedEvent.DisconnectedBy.WARD
        ));
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

    // 보호자-피보호자 간 활성 또는 대기 중 연결 존재 여부 (취소 제외)
    @Transactional(readOnly = true)
    public boolean isConnected(String guardianId, String wardId) {
        return connectionRepository.existsByGuardianIdAndWardIdAndStatusNot(
                guardianId, wardId, ConnectionStatus.CANCELLED);
    }

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

    private List<ConnectionResponse> buildResponseFromGuardianView(List<Connection> connections) {
        List<String> wardIds = connections.stream().map(Connection::getWardId).toList();
        Map<String, User> wardMap = userRepository.findAllById(wardIds).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));
        return connections.stream()
                .map(c -> ConnectionResponse.fromGuardianView(c, wardMap.get(c.getWardId())))
                .toList();
    }

    private List<ConnectionResponse> buildResponseFromWardView(List<Connection> connections) {
        List<String> guardianIds = connections.stream().map(Connection::getGuardianId).toList();
        Map<String, User> guardianMap = userRepository.findAllById(guardianIds).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));
        return connections.stream()
                .map(c -> ConnectionResponse.fromWardView(c, guardianMap.get(c.getGuardianId())))
                .toList();
    }

    private User requireUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }
}

package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.dto.AdminForceConnectRequest;
import kr.silverbridge.main.domain.admin.dto.ConnectionResponse;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.AdminAuditAction;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 관리자용 연결 관계 관리 서비스
 * 연결 목록 조회 및 강제 연결/해제를 담당한다.
 */
@Service
@RequiredArgsConstructor
public class AdminConnectionService {

    private final UserRepository userRepository;
    private final ConnectionRepository connectionRepository;
    private final AdminAuditLogService auditLogService;

    // 전체 연결 관계 조회 (최신 요청순 + 배치 사용자 조회)
    @Transactional(readOnly = true)
    public List<ConnectionResponse> getConnections() {
        List<Connection> connections = connectionRepository.findAll(
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Map<String, User> userMap = fetchUsersFromConnections(connections);

        return connections.stream()
                .map(conn -> ConnectionResponse.of(
                        conn,
                        requireUser(userMap, conn.getGuardianId()),
                        requireUser(userMap, conn.getWardId())
                ))
                .toList();
    }

    // 특정 보호자의 피보호자 목록 조회 (최신 요청순 + 배치 사용자 조회)
    @Transactional(readOnly = true)
    public List<ConnectionResponse> getConnectionsByGuardian(String guardianId) {
        User guardian = userRepository.findById(guardianId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        if (guardian.getRole() != Role.GUARDIAN) {
            throw new CustomException(ErrorCode.INVALID_CONNECTION_ROLE);
        }

        List<Connection> connections = connectionRepository.findByGuardianIdOrderByCreatedAtDesc(guardianId);
        Map<String, User> userMap = fetchUsersFromConnections(connections);

        return connections.stream()
                .map(conn -> ConnectionResponse.of(
                        conn,
                        userMap.getOrDefault(conn.getGuardianId(), guardian),
                        requireUser(userMap, conn.getWardId())
                ))
                .toList();
    }

    // 관리자 강제 연결 (바로 ACTIVE)
    @Transactional
    public ConnectionResponse forceConnect(AdminForceConnectRequest request, String adminId) {
        User guardian = userRepository.findById(request.getGuardianId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        User ward = userRepository.findById(request.getWardId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        if (guardian.getRole() != Role.GUARDIAN || ward.getRole() != Role.WARD) {
            throw new CustomException(ErrorCode.INVALID_CONNECTION_ROLE);
        }

        if (guardian.getStatus() == Status.INACTIVE || ward.getStatus() == Status.INACTIVE) {
            throw new CustomException(ErrorCode.INACTIVE_USER);
        }

        if (connectionRepository.existsByGuardianIdAndWardIdAndStatusNot(
                request.getGuardianId(), request.getWardId(), ConnectionStatus.CANCELLED)) {
            throw new CustomException(ErrorCode.CONNECTION_ALREADY_EXISTS);
        }

        Connection connection = Connection.builder()
                .guardianId(request.getGuardianId())
                .wardId(request.getWardId())
                .status(ConnectionStatus.PENDING)
                .initiatedBy(adminId)
                .build();
        connection.activate();

        Connection saved = connectionRepository.save(connection);

        auditLogService.log(adminId, AdminAuditAction.FORCE_CONNECT, String.valueOf(saved.getId()),
                String.format("강제 연결: guardian=%s, ward=%s", request.getGuardianId(), request.getWardId()));

        return ConnectionResponse.of(saved, guardian, ward);
    }

    // 관리자 강제 연결 해제
    @Transactional
    public void forceDisconnect(Long connectionId, String adminId) {
        Connection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new CustomException(ErrorCode.CONNECTION_NOT_FOUND));

        connection.cancel();

        auditLogService.log(adminId, AdminAuditAction.FORCE_DISCONNECT, String.valueOf(connectionId),
                String.format("강제 연결 해제: guardian=%s, ward=%s",
                        connection.getGuardianId(), connection.getWardId()));
    }

    // Connection 목록에서 모든 사용자 ID를 배치 조회
    private Map<String, User> fetchUsersFromConnections(List<Connection> connections) {
        Set<String> userIds = new HashSet<>();
        connections.forEach(c -> {
            userIds.add(c.getGuardianId());
            userIds.add(c.getWardId());
        });
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
    }

    // 배치 조회 결과에서 사용자 조회 (없으면 예외)
    private User requireUser(Map<String, User> userMap, String userId) {
        User user = userMap.get(userId);
        if (user == null) throw new CustomException(ErrorCode.USER_NOT_FOUND);
        return user;
    }
}

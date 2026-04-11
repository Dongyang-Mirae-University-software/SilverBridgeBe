package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.dto.*;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyEventRepository;
import kr.silverbridge.main.domain.auth.repository.AccessLogRepository;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.game.repository.GameResultRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.GameType;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final AccessLogRepository accessLogRepository;
    private final ConnectionRepository connectionRepository;
    private final AnomalyEventRepository anomalyEventRepository;
    private final GameResultRepository gameResultRepository;

    // 사용자 목록 조회 (페이징, role 필터링)
    // role 미입력 시 WARD + GUARDIAN 전체 조회, ADMIN 제외
    @Transactional(readOnly = true)
    public Page<UserSummaryResponse> getUsers(Role role, Pageable pageable) {
        List<Role> roles = (role != null) ? List.of(role) : List.of(Role.WARD, Role.GUARDIAN);
        return userRepository.findByRoleIn(roles, pageable)
                .map(UserSummaryResponse::from);
    }

    // 사용자 상세 조회
    @Transactional(readOnly = true)
    public UserDetailResponse getUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        return UserDetailResponse.from(user);
    }

    // 피보호자/보호자 계정 상태 변경 (활성화 / 비활성화)
    // ADMIN 계정은 변경 불가
    @Transactional
    public void updateUserStatus(String userId, UserStatusUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        if (user.getRole() == Role.ADMIN) {
            throw new CustomException(ErrorCode.CANNOT_MODIFY_ADMIN);
        }

        switch (request.getStatus()) {
            case ACTIVE   -> user.activate();
            case INACTIVE -> user.deactivate();
            default       -> throw new CustomException(ErrorCode.INVALID_STATUS);
        }
    }

    // 사용자 역할 변경 (WARD ↔ GUARDIAN)
    // ADMIN 계정 변경 불가, ADMIN으로 변경 불가
    @Transactional
    public void updateUserRole(String userId, UserRoleUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        if (user.getRole() == Role.ADMIN) {
            throw new CustomException(ErrorCode.CANNOT_MODIFY_ADMIN);
        }

        if (request.getRole() == Role.ADMIN) {
            throw new CustomException(ErrorCode.INVALID_ROLE);
        }

        user.updateRole(request.getRole());
    }

    // 사용자 강제 탈퇴 (계정 영구 삭제)
    // ADMIN 계정은 삭제 불가
    @Transactional
    public void forceDeleteUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        if (user.getRole() == Role.ADMIN) {
            throw new CustomException(ErrorCode.CANNOT_MODIFY_ADMIN);
        }

        userRepository.delete(user);
    }

    // 전체 연결 관계 조회 (페이징)
    @Transactional(readOnly = true)
    public Page<ConnectionResponse> getConnections(Pageable pageable) {
        return connectionRepository.findAll(pageable)
                .map(conn -> {
                    User guardian = userRepository.findById(conn.getGuardianId())
                            .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
                    User ward = userRepository.findById(conn.getWardId())
                            .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
                    return ConnectionResponse.of(conn, guardian, ward);
                });
    }

    // 특정 보호자의 피보호자 목록 조회
    @Transactional(readOnly = true)
    public Page<ConnectionResponse> getConnectionsByGuardian(String guardianId, Pageable pageable) {
        userRepository.findById(guardianId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        return connectionRepository.findByGuardianId(guardianId, pageable)
                .map(conn -> {
                    User guardian = userRepository.findById(conn.getGuardianId())
                            .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
                    User ward = userRepository.findById(conn.getWardId())
                            .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
                    return ConnectionResponse.of(conn, guardian, ward);
                });
    }

    // 관리자 강제 연결 (바로 ACTIVE)
    @Transactional
    public ConnectionResponse forceConnect(AdminForceConnectRequest request, String adminId) {
        User guardian = userRepository.findById(request.getGuardianId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        User ward = userRepository.findById(request.getWardId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 역할 검증
        if (guardian.getRole() != Role.GUARDIAN || ward.getRole() != Role.WARD) {
            throw new CustomException(ErrorCode.INVALID_CONNECTION_ROLE);
        }

        // 이미 연결 중인지 확인 (CANCELLED 제외)
        if (connectionRepository.existsByGuardianIdAndWardIdAndStatusNot(
                request.getGuardianId(), request.getWardId(), ConnectionStatus.CANCELLED)) {
            throw new CustomException(ErrorCode.CONNECTION_ALREADY_EXISTS);
        }

        Connection connection = Connection.builder()
                .guardianId(request.getGuardianId())
                .wardId(request.getWardId())
                .status(ConnectionStatus.ACTIVE)
                .initiatedBy(adminId)
                .build();
        connection.activate();

        return ConnectionResponse.of(connectionRepository.save(connection), guardian, ward);
    }

    // 관리자 강제 연결 해제
    @Transactional
    public void forceDisconnect(Long connectionId) {
        Connection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new CustomException(ErrorCode.CONNECTION_NOT_FOUND));

        connection.cancel();
    }

    // 이상감지 이벤트 조회 (관리자용 — 날짜 범위 + 보호자 필터)
    // guardianId 미입력 시 전체 조회, 입력 시 해당 보호자의 피보호자 이벤트만 조회
    @Transactional(readOnly = true)
    public Page<AnomalyEventResponse> getAnomalyEvents(
            String guardianId,
            OffsetDateTime startDate,
            OffsetDateTime endDate,
            Pageable pageable
    ) {
        Page<kr.silverbridge.main.domain.anomaly.entity.AnomalyEvent> events;

        if (guardianId != null) {
            // 보호자가 존재하는지 확인
            userRepository.findById(guardianId)
                    .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

            List<String> wardIds = anomalyEventRepository.findActiveWardIdsByGuardianId(guardianId);
            if (wardIds.isEmpty()) {
                return Page.empty(pageable);
            }
            events = anomalyEventRepository.findByWardIdsAndDateRange(wardIds, startDate, endDate, pageable);
        } else {
            events = anomalyEventRepository.findByDateRange(startDate, endDate, pageable);
        }

        return events.map(event -> {
            if (event.getWardId() == null) {
                return AnomalyEventResponse.ofDeleted(event);
            }
            User ward = userRepository.findById(event.getWardId()).orElse(null);
            return ward != null
                    ? AnomalyEventResponse.of(event, ward)
                    : AnomalyEventResponse.ofDeleted(event);
        });
    }

    // 게임 결과 조회 (관리자용 — 피보호자 + 게임 유형 + 날짜 범위 필터)
    @Transactional(readOnly = true)
    public Page<GameResultResponse> getGameResults(
            String userId,
            GameType gameType,
            OffsetDateTime startDate,
            OffsetDateTime endDate,
            Pageable pageable
    ) {
        if (userId != null) {
            userRepository.findById(userId)
                    .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        }

        return gameResultRepository.findByFilters(userId, gameType, startDate, endDate, pageable)
                .map(result -> {
                    User user = userRepository.findById(result.getUserId())
                            .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
                    return GameResultResponse.of(result, user);
                });
    }

    // 접속 로그 조회 (페이징)
    @Transactional(readOnly = true)
    public Page<AccessLogResponse> getAccessLogs(Pageable pageable) {
        return accessLogRepository.findAll(pageable)
                .map(AccessLogResponse::from);
    }
}

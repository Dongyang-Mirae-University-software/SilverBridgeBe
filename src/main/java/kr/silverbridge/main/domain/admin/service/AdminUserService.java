package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.dto.UserDetailResponse;
import kr.silverbridge.main.domain.admin.dto.UserRoleUpdateRequest;
import kr.silverbridge.main.domain.admin.dto.UserStatusUpdateRequest;
import kr.silverbridge.main.domain.admin.dto.UserSummaryResponse;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.AdminAuditAction;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 관리자용 사용자 관리 서비스
 * 사용자 조회/상태변경/역할변경/강제탈퇴를 담당한다.
 * 역할 변경 시 연결 자동 정리를 포함한다.
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final ConnectionRepository connectionRepository;
    private final AdminAuditLogService auditLogService;

    // 사용자 목록 조회 (페이징, role 필터링)
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
    @Transactional
    public void updateUserStatus(String userId, UserStatusUpdateRequest request, String adminId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        validateNotAdmin(user);

        String oldStatus = user.getStatus().name();
        switch (request.getStatus()) {
            case ACTIVE   -> user.activate();
            case INACTIVE -> user.deactivate();
            default       -> throw new CustomException(ErrorCode.INVALID_STATUS);
        }

        auditLogService.log(adminId, AdminAuditAction.USER_STATUS_CHANGE, userId,
                String.format("상태 변경: %s → %s", oldStatus, request.getStatus().name()));
    }

    // 사용자 역할 변경 (WARD ↔ GUARDIAN)
    // 역할 변경 시 기존 ACTIVE/PENDING 연결 자동 CANCELLED 처리 (데이터 정합성)
    @Transactional
    public void updateUserRole(String userId, UserRoleUpdateRequest request, String adminId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        validateNotAdmin(user);

        if (request.getRole() == Role.ADMIN) {
            throw new CustomException(ErrorCode.INVALID_ROLE);
        }

        String oldRole = user.getRole().name();
        user.updateRole(request.getRole());

        // 역할 변경 시 기존 연결 관계 정리 (보호자/피보호자로서의 연결 모두 해제)
        List<Connection> activeConnections = connectionRepository.findActiveByUserId(
                userId, List.of(ConnectionStatus.PENDING, ConnectionStatus.ACTIVE)
        );
        activeConnections.forEach(Connection::cancel);

        auditLogService.log(adminId, AdminAuditAction.USER_ROLE_CHANGE, userId,
                String.format("역할 변경: %s → %s (연결 %d건 해제)",
                        oldRole, request.getRole().name(), activeConnections.size()));
    }

    // 사용자 강제 탈퇴 (계정 영구 삭제)
    @Transactional
    public void forceDeleteUser(String userId, String adminId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        validateNotAdmin(user);

        String email = user.getEmail();
        userRepository.delete(user);

        auditLogService.log(adminId, AdminAuditAction.USER_FORCE_DELETE, userId,
                String.format("강제 탈퇴: %s", email));
    }

    // ADMIN 계정 수정/삭제 차단
    private void validateNotAdmin(User user) {
        if (user.getRole() == Role.ADMIN) {
            throw new CustomException(ErrorCode.CANNOT_MODIFY_ADMIN);
        }
    }
}

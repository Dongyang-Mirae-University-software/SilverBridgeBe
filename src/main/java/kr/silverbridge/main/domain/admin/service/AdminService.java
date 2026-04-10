package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.dto.*;
import kr.silverbridge.main.domain.auth.repository.AccessLogRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final AccessLogRepository accessLogRepository;

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

    // 접속 로그 조회 (페이징)
    @Transactional(readOnly = true)
    public Page<AccessLogResponse> getAccessLogs(Pageable pageable) {
        return accessLogRepository.findAll(pageable)
                .map(AccessLogResponse::from);
    }
}

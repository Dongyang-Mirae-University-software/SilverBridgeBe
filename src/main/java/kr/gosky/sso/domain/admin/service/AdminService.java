package kr.gosky.sso.domain.admin.service;

import kr.gosky.sso.domain.admin.dto.*;
import kr.gosky.sso.domain.auth.repository.AccessLogRepository;
import kr.gosky.sso.domain.user.entity.User;
import kr.gosky.sso.domain.user.repository.UserRepository;
import kr.gosky.sso.global.exception.CustomException;
import kr.gosky.sso.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final AccessLogRepository accessLogRepository;

    // 사용자 목록 조회 (페이징)
    @Transactional(readOnly = true)
    public Page<UserSummaryResponse> getUsers(Pageable pageable) {
        return userRepository.findAll(pageable)
                .map(UserSummaryResponse::from);
    }

    // 사용자 상세 조회
    @Transactional(readOnly = true)
    public UserDetailResponse getUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        return UserDetailResponse.from(user);
    }

    // 사용자 상태 변경 (활성화 / 비활성화)
    @Transactional
    public void updateUserStatus(String userId, UserStatusUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        switch (request.getStatus()) {
            case ACTIVE   -> user.activate();
            case INACTIVE -> user.deactivate();
        }
    }

    // 접속 로그 조회 (페이징)
    @Transactional(readOnly = true)
    public Page<AccessLogResponse> getAccessLogs(Pageable pageable) {
        return accessLogRepository.findAll(pageable)
                .map(AccessLogResponse::from);
    }
}

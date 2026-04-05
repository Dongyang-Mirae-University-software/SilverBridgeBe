package kr.gosky.sso.domain.user.service;

import kr.gosky.sso.domain.auth.repository.RefreshTokenRepository;
import kr.gosky.sso.domain.user.dto.UserProfileResponse;
import kr.gosky.sso.domain.user.entity.User;
import kr.gosky.sso.domain.user.repository.UserRepository;
import kr.gosky.sso.global.exception.CustomException;
import kr.gosky.sso.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;

    // 내 정보 조회
    @Transactional(readOnly = true)
    public UserProfileResponse getMyProfile(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        return UserProfileResponse.from(user);
    }

    // 비밀번호 변경
    // 현재 비밀번호 확인 후 새 비밀번호로 교체, 기존 Refresh Token 모두 삭제
    @Transactional
    public void changePassword(String userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new CustomException(ErrorCode.INVALID_PASSWORD);
        }

        user.updatePassword(passwordEncoder.encode(newPassword));
        // 비밀번호 변경 후 모든 기기에서 강제 로그아웃 처리
        refreshTokenRepository.deleteByUserId(userId);
    }

    // 회원 탈퇴
    // 비밀번호 확인 후 계정 비활성화, Refresh Token 삭제
    @Transactional
    public void withdraw(String userId, String password) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new CustomException(ErrorCode.INVALID_PASSWORD);
        }

        user.deactivate();
        refreshTokenRepository.deleteByUserId(userId);
    }
}

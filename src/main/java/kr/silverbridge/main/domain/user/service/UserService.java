package kr.silverbridge.main.domain.user.service;

import kr.silverbridge.main.domain.auth.repository.RefreshTokenRepository;
import kr.silverbridge.main.domain.user.dto.UserProfileResponse;
import kr.silverbridge.main.domain.user.dto.UserUpdateRequest;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
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

    // 내 정보 수정 (이름, 전화번호)
    @Transactional
    public UserProfileResponse updateProfile(String userId, UserUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        user.updateProfile(request.getName(), request.getPhone());
        return UserProfileResponse.from(user);
    }

    // 비밀번호 변경
    // 현재 비밀번호 확인 후 새 비밀번호로 교체, 기존 Refresh Token 모두 삭제
    @Transactional
    public void changePassword(String userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 소셜 로그인 사용자는 비밀번호 변경 불가
        if (user.getProvider() != Provider.LOCAL) {
            throw new CustomException(ErrorCode.SOCIAL_USER_NO_PASSWORD);
        }

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new CustomException(ErrorCode.INVALID_PASSWORD);
        }

        // 현재 비밀번호와 동일한 경우 차단
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new CustomException(ErrorCode.SAME_AS_CURRENT_PASSWORD);
        }

        // 이전 비밀번호 2개와 중복 검사
        if (user.getPrevPassword1() != null && passwordEncoder.matches(newPassword, user.getPrevPassword1())) {
            throw new CustomException(ErrorCode.PASSWORD_RECENTLY_USED);
        }
        if (user.getPrevPassword2() != null && passwordEncoder.matches(newPassword, user.getPrevPassword2())) {
            throw new CustomException(ErrorCode.PASSWORD_RECENTLY_USED);
        }

        // 비밀번호 변경 (이력 자동 보관)
        user.updatePassword(passwordEncoder.encode(newPassword));
        // 비밀번호 변경 후 모든 기기에서 강제 로그아웃 처리
        refreshTokenRepository.deleteByUserId(userId);
    }

    // 회원 탈퇴
    // 일반 사용자: 비밀번호 확인 후 비활성화 / 소셜 사용자: 비밀번호 없이 비활성화
    @Transactional
    public void withdraw(String userId, String password) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        if (user.getProvider() == Provider.LOCAL) {
            // 일반 로그인 사용자: 비밀번호 검증 필수
            if (password == null || password.isBlank()) {
                throw new CustomException(ErrorCode.INVALID_PASSWORD);
            }
            if (!passwordEncoder.matches(password, user.getPassword())) {
                throw new CustomException(ErrorCode.INVALID_PASSWORD);
            }
        }
        // 소셜 로그인 사용자: 비밀번호 검증 없이 탈퇴

        user.deactivate();
        refreshTokenRepository.deleteByUserId(userId);
    }
}

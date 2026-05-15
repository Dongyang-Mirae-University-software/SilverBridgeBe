package kr.silverbridge.main.domain.user.service;

import kr.silverbridge.main.domain.auth.service.SmsService;
import kr.silverbridge.main.domain.user.dto.PasswordChangeRequest;
import kr.silverbridge.main.domain.user.dto.UserProfileResponse;
import kr.silverbridge.main.domain.user.dto.UserUpdateRequest;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.event.PasswordChangedEvent;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.client.FileServerClient;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024L; // 5MB
    private static final List<String> ALLOWED_CONTENT_TYPES =
            List.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final FileServerClient fileServerClient;
    private final ApplicationEventPublisher eventPublisher;
    private final SmsService smsService;

    // 내 정보 조회
    @Transactional(readOnly = true)
    public UserProfileResponse getMyProfile(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        return UserProfileResponse.from(user);
    }

    // 내 정보 수정 (이름, 전화번호)
    // 전화번호 변경 시 SMS 인증 완료 여부 및 중복 확인
    @Transactional
    public UserProfileResponse updateProfile(String userId, UserUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        String newPhone = request.getPhone();
        if (newPhone != null && !newPhone.equals(user.getPhone())) {
            // SMS 인증 nonce 일치 확인 + 키 소비 (H-5)
            smsService.consumeVerification(newPhone, request.getVerificationNonce());
            // 다른 계정이 이미 사용 중인 전화번호인지 확인
            if (userRepository.existsByPhone(newPhone)) {
                throw new CustomException(ErrorCode.PHONE_ALREADY_EXISTS);
            }
        }

        user.updateProfile(
                request.getName(),
                newPhone != null ? newPhone : user.getPhone(),
                request.getAddress(),
                request.getAddressDetail()
        );
        return UserProfileResponse.from(user);
    }

    // 비밀번호 변경
    // 현재 비밀번호 확인 후 새 비밀번호로 교체, 기존 Refresh Token은 이벤트로 정리
    @Transactional
    public void changePassword(String userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 소셜 로그인 사용자는 비밀번호 변경 불가
        if (user.isSocialProvider()) {
            throw new CustomException(ErrorCode.SOCIAL_USER_NO_PASSWORD);
        }

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new CustomException(ErrorCode.INVALID_PASSWORD);
        }

        // 현재 비밀번호와 동일한 경우 차단
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new CustomException(ErrorCode.SAME_AS_CURRENT_PASSWORD);
        }

        user.updatePassword(passwordEncoder.encode(newPassword));
        eventPublisher.publishEvent(new PasswordChangedEvent(userId));
    }

    // 프로필 이미지 변경
    // 파일 서버에 업로드 후 반환된 URL을 DB에 저장
    @Transactional
    public UserProfileResponse updateProfileImage(String userId, MultipartFile file) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 파일 크기 제한 (5MB)
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new CustomException(ErrorCode.FILE_TOO_LARGE);
        }

        // 이미지 파일 형식 제한
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new CustomException(ErrorCode.INVALID_FILE_TYPE);
        }

        String oldImageUrl = user.getProfileImage();
        String newImageUrl = fileServerClient.upload(file);
        user.updateProfileImage(newImageUrl);

        // 업로드 성공 후 기존 이미지 삭제 (실패해도 주 기능에 영향 없음)
        fileServerClient.delete(oldImageUrl);

        return UserProfileResponse.from(user);
    }

    // 회원 탈퇴
    // 일반 사용자: 비밀번호 확인 후 비활성화 / 소셜 사용자: 비밀번호 없이 비활성화
    // 토큰 정리·접속 로그는 UserWithdrawnEvent를 통해 auth 도메인에서 처리
    @Transactional
    public void withdraw(String userId, String password, String ipAddress, String userAgent) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        if (user.isLocalProvider()) {
            if (!passwordEncoder.matches(password, user.getPassword())) {
                throw new CustomException(ErrorCode.INVALID_PASSWORD);
            }
        }

        user.deactivate();
        eventPublisher.publishEvent(new UserWithdrawnEvent(userId, ipAddress, userAgent));
    }
}

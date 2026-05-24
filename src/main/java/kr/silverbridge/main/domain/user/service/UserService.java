package kr.silverbridge.main.domain.user.service;

import kr.silverbridge.main.domain.user.dto.UserProfileResponse;
import kr.silverbridge.main.domain.user.dto.UserUpdateRequest;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.event.PasswordChangedEvent;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
import kr.silverbridge.main.domain.user.port.PhoneVerificationPort;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.client.FileServerClient;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024L; // 5MB
    private static final List<String> ALLOWED_CONTENT_TYPES =
            List.of("image/jpeg", "image/png", "image/webp", "image/gif");
    // 실제 파일 시그니처(Magic Number) 확인용 — 앞부분 바이트 길이 (WebP의 "RIFF....WEBP" 검증에 12바이트 필요)
    private static final int IMAGE_SIGNATURE_LENGTH = 12;
    // 카카오 사용자 탈퇴 본인 확인 문자열 (H-6)
    private static final String KAKAO_WITHDRAW_CONFIRMATION = "탈퇴";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final FileServerClient fileServerClient;
    private final ApplicationEventPublisher eventPublisher;
    private final PhoneVerificationPort phoneVerificationPort;

    // 내 정보 조회
    @Transactional(readOnly = true)
    public UserProfileResponse getMyProfile(String userId) {
        User user = getUserOrThrow(userId);
        return UserProfileResponse.from(user);
    }

    // 내 정보 수정 (이름, 전화번호)
    // 전화번호 변경 시 SMS 인증 완료 여부 및 중복 확인
    @Transactional
    public UserProfileResponse updateProfile(String userId, UserUpdateRequest request) {
        User user = getUserOrThrow(userId);

        String newPhone = request.getPhone();
        if (newPhone != null && !newPhone.equals(user.getPhone())) {
            // SMS 인증 nonce 일치 확인 + 키 소비 (H-5). user→auth 직접 의존 대신 포트 경유 (B-1)
            phoneVerificationPort.consumeVerification(newPhone, request.getVerificationNonce());
            // 다른 계정이 이미 사용 중인 전화번호인지 확인
            if (userRepository.existsByPhone(newPhone)) {
                throw new CustomException(ErrorCode.PHONE_ALREADY_EXISTS);
            }
        }

        user.updateProfile(
                request.getName(),
                newPhone != null ? newPhone : user.getPhone(),
                request.getGender(),
                request.getBirthDate(),
                request.getPostcode(),
                request.getAddress(),
                request.getAddressDetail()
        );
        return UserProfileResponse.from(user);
    }

    // 비밀번호 변경
    // 현재 비밀번호 확인 후 새 비밀번호로 교체, 기존 Refresh Token은 이벤트로 정리
    @Transactional
    public void changePassword(String userId, String currentPassword, String newPassword) {
        User user = getUserOrThrow(userId);

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
        // 보안 핵심 이벤트 감사 로그 (E-USER-1) — PII 미포함, userId만
        log.info("[PASSWORD-CHANGE] 비밀번호 변경 완료, 전 기기 토큰 무효화 userId={}", userId);
    }

    // 프로필 이미지 변경
    // 파일 서버에 업로드 후 반환된 URL을 DB에 저장
    @Transactional
    public UserProfileResponse updateProfileImage(String userId, MultipartFile file) {
        log.info("[PROFILE-IMAGE] 변경 요청 수신 userId={}", userId);
        User user = getUserOrThrow(userId);

        validateImage(file);

        String oldImageUrl = user.getProfileImage();
        String newImageUrl = fileServerClient.upload(file);
        user.updateProfileImage(newImageUrl);

        // 기존 이미지는 커밋 이후 삭제 (롤백 시 깨진 이미지 방지, D-USER-2). 실패해도 주 기능에 영향 없음.
        deleteStoredFileAfterCommit(oldImageUrl);

        log.info("[PROFILE-IMAGE] 변경 완료 userId={}", userId);
        return UserProfileResponse.from(user);
    }

    // 프로필 이미지 삭제 (기본 이미지로 되돌림)
    // 멱등: 이미 이미지가 없으면 그대로 종료. 값이 있으면 DB를 먼저 NULL 처리(진실의 원천)한 뒤
    // 파일 서버 실제 파일 삭제를 위임한다. 교체 시 자동 삭제(updateProfileImage)와 동일한 fire-and-forget 패턴.
    // 반환값: 실제로 삭제한 경우 true / 이미 이미지가 없어 삭제할 대상이 없던 경우 false (둘 다 200, 안내 메시지 분기용)
    @Transactional
    public boolean deleteProfileImage(String userId) {
        log.info("[PROFILE-IMAGE] 삭제 요청 수신 userId={}", userId);

        User user = getUserOrThrow(userId);

        String oldImageUrl = user.getProfileImage();
        if (oldImageUrl == null || oldImageUrl.isBlank()) {
            // 멱등 처리: 이미 이미지가 없으므로 삭제할 대상 없음 (기본 이미지 상태)
            log.info("[PROFILE-IMAGE] 삭제 대상 없음, 멱등 처리 userId={}", userId);
            return false;
        }

        // DB가 진실의 원천: 파일 서버 결과와 무관하게 NULL 로 비운다
        user.updateProfileImage(null);

        // 파일 서버 실제 파일 삭제는 커밋 이후로 위임 (D-USER-2). FileServerClient.delete 는 실패 시 WARN 로깅만 함
        deleteStoredFileAfterCommit(oldImageUrl);

        log.info("[PROFILE-IMAGE] 삭제 완료 userId={}", userId);
        return true;
    }

    // 회원 탈퇴
    // 일반 사용자: 비밀번호 확인 후 비활성화
    // 카카오 사용자: confirmation 문자열("탈퇴") 일치 확인 후 비활성화 — access token 단독 탈취 시 영구 비활성화 차단(H-6)
    // 토큰 정리·접속 로그는 UserWithdrawnEvent를 통해 auth 도메인에서 처리
    @Transactional
    public void withdraw(String userId, String password, String confirmation, String ipAddress, String userAgent) {
        User user = getUserOrThrow(userId);

        if (user.isLocalProvider()) {
            if (!passwordEncoder.matches(password, user.getPassword())) {
                throw new CustomException(ErrorCode.INVALID_PASSWORD);
            }
        } else {
            // 카카오 사용자 본인 확인 — confirmation 문자열 일치
            if (confirmation == null || !KAKAO_WITHDRAW_CONFIRMATION.equals(confirmation.trim())) {
                throw new CustomException(ErrorCode.WITHDRAW_CONFIRMATION_MISMATCH);
            }
        }

        user.deactivate();
        eventPublisher.publishEvent(new UserWithdrawnEvent(userId, ipAddress, userAgent));
    }

    // userId로 사용자 조회 (없으면 USER_NOT_FOUND) — 전 메서드 공통 진입점 (B-USER-2)
    private User getUserOrThrow(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }

    // 업로드 이미지 검증: 크기 → 선언 Content-Type(화이트리스트) → 실제 파일 시그니처(Magic Number) 순.
    // Content-Type 헤더는 클라이언트가 위조할 수 있어, 실제 바이트 시그니처까지 함께 확인한다 (A-USER-2).
    private void validateImage(MultipartFile file) {
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new CustomException(ErrorCode.FILE_TOO_LARGE);
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new CustomException(ErrorCode.INVALID_FILE_TYPE);
        }
        if (!hasAllowedImageSignature(file)) {
            throw new CustomException(ErrorCode.INVALID_FILE_TYPE);
        }
    }

    // 파일 앞부분 바이트로 실제 이미지 포맷(JPEG/PNG/GIF/WebP)인지 확인 (확장자·Content-Type 위조 방어)
    private boolean hasAllowedImageSignature(MultipartFile file) {
        byte[] h;
        try (InputStream is = file.getInputStream()) {
            h = is.readNBytes(IMAGE_SIGNATURE_LENGTH);
        } catch (IOException e) {
            return false;
        }
        // JPEG: FF D8 FF
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return true;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G'
                && (h[4] & 0xFF) == 0x0D && (h[5] & 0xFF) == 0x0A && (h[6] & 0xFF) == 0x1A && (h[7] & 0xFF) == 0x0A) {
            return true;
        }
        // GIF: "GIF87a" / "GIF89a"
        if (h.length >= 6 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8'
                && (h[4] == '7' || h[4] == '9') && h[5] == 'a') {
            return true;
        }
        // WebP: "RIFF" .... "WEBP"
        if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return true;
        }
        return false;
    }

    // 파일 서버 실제 파일 삭제를 트랜잭션 커밋 이후로 미룬다 (D-USER-2).
    // 커밋 전 삭제 시 트랜잭션이 롤백되면 DB는 옛 URL을 가리키는데 파일은 사라져 이미지가 깨지므로 afterCommit 에서만 삭제한다.
    // 트랜잭션이 없는 경우(단위 테스트 등)에는 즉시 위임한다.
    private void deleteStoredFileAfterCommit(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    fileServerClient.delete(fileUrl);
                }
            });
        } else {
            fileServerClient.delete(fileUrl);
        }
    }
}

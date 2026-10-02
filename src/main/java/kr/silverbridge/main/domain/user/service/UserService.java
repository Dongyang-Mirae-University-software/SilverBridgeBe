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
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.util.RedisCounter;
import kr.silverbridge.main.global.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
    // 카카오 가입자 탈퇴 확인 문구. 두 가지를 모두 받는다(2026-10-01 QA BE-2): FE 안내는 "회원탈퇴"인데 BE가 "탈퇴"만
    // 받아 어떤 입력으로도 탈퇴할 수 없었다. 둘 다 사용자가 직접 입력해야 하는 문구라 본인 확인 강도는 같다.
    private static final Set<String> KAKAO_WITHDRAW_CONFIRMATIONS = Set.of("탈퇴", "회원탈퇴");
    // 비밀번호 변경·탈퇴의 현재 비밀번호 확인 시도 제한 (USER-G05) - 로그인 잠금(5회·30분)과 같은 강도.
    // access token만 탈취한 쪽이 이 경로로 비밀번호를 무제한 대입해 계정을 장악하던 틈을 막는다.
    // 로그인 설정(AuthLoginProperties)은 auth 도메인이라 user → auth 의존을 만들지 않으려고 값을 여기 둔다.
    private static final int MAX_PASSWORD_CHECK_ATTEMPTS = 5;
    private static final long PASSWORD_CHECK_LOCK_MINUTES = 30L;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final FileServerClient fileServerClient;
    private final ApplicationEventPublisher eventPublisher;
    private final PhoneVerificationPort phoneVerificationPort;
    private final ProfileImagePersister profileImagePersister;
    private final StringRedisTemplate redisTemplate;
    private final RedisCounter redisCounter;

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
            // 다른 계정이 이미 사용 중인 전화번호인지 먼저 확인한다 (USER-G11, "검증 후 마지막 소비").
            // 소비가 앞이면 409로 끝나도 nonce가 이미 지워져(Redis 삭제는 롤백 대상이 아님) 문자 인증을 다시 받아야 했다.
            // 가입 SMS 발송이 이미 가입 번호를 409로 알려 주므로 순서를 바꿔도 새로 드러나는 정보는 없다.
            if (userRepository.existsByPhone(newPhone)) {
                throw new CustomException(ErrorCode.PHONE_ALREADY_EXISTS);
            }
            // SMS 인증 nonce 일치 확인 + 키 소비 (H-5). user→auth 직접 의존 대신 포트 경유 (B-1)
            // 커밋 시점 유니크 위반(동시 선점)으로 실패하면 nonce는 복구되지 않는다 - 가입 nonce의 L-1과 같은 이유로 수용한다.
            phoneVerificationPort.consumeVerification(newPhone, request.getVerificationNonce());
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

        verifyCurrentPassword(user, currentPassword);

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
    // 업로드(외부 HTTP)는 트랜잭션 밖에서 수행하고, URL 영속화만 ProfileImagePersister(@Transactional)에 위임한다 (D-USER-1).
    // 이 메서드 자체에는 DB 접근이 없어 @Transactional 을 두지 않는다 (업로드 동안 커넥션 미점유).
    public UserProfileResponse updateProfileImage(String userId, MultipartFile file) {
        log.info("[PROFILE-IMAGE] 변경 요청 수신 userId={}", userId);
        validateImage(file);

        // 파일 서버 업로드 — 트랜잭션 밖 (D-USER-1)
        String newImageUrl = fileServerClient.upload(file);

        // URL 영속화는 별도 트랜잭션(프록시 경유, dirty checking → updated_at 갱신 유지).
        // 영속화 실패 시 방금 업로드한 파일이 고아로 남으므로 fire-and-forget 정리 후 원예외 전파 (L-S1-5)
        ProfileImagePersister.Result result;
        try {
            result = profileImagePersister.replace(userId, newImageUrl);
        } catch (RuntimeException e) {
            fileServerClient.delete(newImageUrl);
            throw e;
        }

        // 영속화 커밋 이후 기존 파일 삭제 — 롤백 시 깨진 이미지 방지 (D-USER-2). 실패해도 주 기능 영향 없음.
        fileServerClient.delete(result.oldImageUrl());

        log.info("[PROFILE-IMAGE] 변경 완료 userId={}", userId);
        return result.response();
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

    // 회원 탈퇴 (1단계) — 본인 확인 + 비활성화 + UserWithdrawnEvent 발행.
    // 일반 사용자: 비밀번호 확인 / 카카오 사용자: confirmation 문자열("탈퇴" 또는 "회원탈퇴") 일치 확인(H-6).
    // deactivate()로 즉시 로그인을 막고, 토큰 정리·접속 로그·연결 해제(상대 알림)는 리스너가
    // user 행이 살아있는 AFTER_COMMIT 시점에 처리한다. 그 직후 컨트롤러가 purgeWithdrawnUser()로 행을 영구 삭제한다.
    @Transactional
    public void withdraw(String userId, String password, String confirmation, String ipAddress, String userAgent) {
        User user = getUserOrThrow(userId);

        // 관리자 계정은 본인 탈퇴 불가 (USER-G08, 결정 D4-A) - 비밀번호 확인보다 먼저 막는다.
        // 관리자는 DB로만 만들어져 마지막 관리자가 스스로 지우면 운영 주체가 사라진다(회원관리의 CANNOT_MODIFY_ADMIN과 같은 취지).
        if (user.getRole() == Role.ADMIN) {
            log.warn("[WITHDRAW-ADMIN-BLOCKED] 관리자 본인 탈퇴 시도 차단 userId={}", userId);
            throw new CustomException(ErrorCode.ADMIN_CANNOT_WITHDRAW);
        }

        if (user.isLocalProvider()) {
            verifyCurrentPassword(user, password);
        } else {
            // 카카오 사용자 본인 확인 — confirmation 문자열 일치
            if (confirmation == null || !KAKAO_WITHDRAW_CONFIRMATIONS.contains(confirmation.trim())) {
                throw new CustomException(ErrorCode.WITHDRAW_CONFIRMATION_MISMATCH);
            }
        }

        user.deactivate();
        eventPublisher.publishEvent(new UserWithdrawnEvent(userId, ipAddress, userAgent));
    }

    // 관리자 강제 탈퇴 (1단계) - 본인 확인만 없고 나머지는 일반 탈퇴와 같은 경로를 탄다.
    // userRepository.delete()를 직접 부르면 AFTER_COMMIT 리스너를 건너뛰어 연결 상대 알림·FCM 토큰 정리·
    // WITHDRAW 접속로그가 통째로 유실된다(행 정리는 FK CASCADE가 하므로 겉보기엔 성공한 것처럼 보인다).
    // 호출자(AdminUserService)가 커밋 후 purgeWithdrawnUser()로 이어 삭제한다.
    // 접속로그의 주체는 탈퇴당한 회원 본인으로 남고, "누가 지웠는가"는 admin_audit_log가 답한다.
    @Transactional
    public void forceWithdraw(String userId, String ipAddress, String userAgent) {
        User user = getUserOrThrow(userId);
        user.deactivate();
        eventPublisher.publishEvent(new UserWithdrawnEvent(userId, ipAddress, userAgent));
    }

    // 회원 탈퇴 (2단계) — 사용자 행을 영구 삭제(hard delete)한다.
    // withdraw() 커밋 후, 그 AFTER_COMMIT 리스너(연결 해제+상대 알림, FCM·refresh 토큰 정리, WITHDRAW 로그)가
    // user 행이 살아있는 동안 모두 끝난 뒤 컨트롤러에서 이어 호출한다.
    // 행 삭제 시 FK 제약에 따라 connections·fcm_tokens·refresh_tokens 는 CASCADE 삭제되고,
    // access_logs·announcements 는 user_id 가 NULL 로 익명화된다(감사 기록 보존). 같은 이메일/전화번호 재가입 가능.
    // 멱등: 이미 삭제됐으면 조용히 종료.
    @Transactional
    public void purgeWithdrawnUser(String userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return;
        }
        String profileImageUrl = user.getProfileImage();
        userRepository.delete(user);
        // 업로드한 프로필 이미지 파일은 커밋 이후 fire-and-forget 으로 제거 (D-USER-2).
        // 카카오 CDN 등 외부 URL이면 파일서버가 대상 파일을 못 찾아 WARN 로깅만 하고 넘어간다.
        deleteStoredFileAfterCommit(profileImageUrl);
        log.info("[WITHDRAW] 계정 영구 삭제 완료 userId={}", userId);
    }

    // 현재 비밀번호 확인 + 시도 제한 (USER-G05) - 비밀번호 변경·탈퇴 공용.
    // 로그인과 같은 방식으로 비교 "전에" 시도 횟수를 원자적으로 예약하고, 한도를 넘기면 비교 없이 429(LOGIN_LOCKED, 30분)다.
    // 키는 userId 기준 user:pwfail/user:pwlock - 로그인 키(login:*)와 섞지 않는다. 확인에 성공하면 카운터를 지운다.
    // Redis 장애 시 RedisCounter 예외가 그대로 나가 요청이 실패한다(fail-closed, 로그인과 같다).
    private void verifyCurrentPassword(User user, String rawPassword) {
        String failKey = RedisKeys.USER_PW_FAIL + user.getId();
        String lockKey = RedisKeys.USER_PW_LOCK + user.getId();

        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey))) {
            throw new CustomException(ErrorCode.LOGIN_LOCKED);
        }
        // 비밀번호를 아예 보내지 않은 요청은 추측 시도가 아니라 횟수에 넣지 않는다(이전엔 BCrypt가 null을 거부해 400이었다)
        if (rawPassword == null) {
            throw new CustomException(ErrorCode.INVALID_PASSWORD);
        }
        long attempts = redisCounter.incrementWithTtl(failKey, PASSWORD_CHECK_LOCK_MINUTES * 60);
        if (attempts > MAX_PASSWORD_CHECK_ATTEMPTS) {
            redisTemplate.opsForValue().setIfAbsent(lockKey, "1", PASSWORD_CHECK_LOCK_MINUTES, TimeUnit.MINUTES);
            throw new CustomException(ErrorCode.LOGIN_LOCKED);
        }
        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
            if (attempts >= MAX_PASSWORD_CHECK_ATTEMPTS) {
                redisTemplate.opsForValue().set(lockKey, "1", PASSWORD_CHECK_LOCK_MINUTES, TimeUnit.MINUTES);
                log.warn("[PASSWORD-CHECK-LOCK] 현재 비밀번호 연속 실패로 잠금 userId={}, 실패 {}회 → {}분",
                        user.getId(), attempts, PASSWORD_CHECK_LOCK_MINUTES);
            }
            throw new CustomException(ErrorCode.INVALID_PASSWORD);
        }
        redisTemplate.delete(failKey);
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

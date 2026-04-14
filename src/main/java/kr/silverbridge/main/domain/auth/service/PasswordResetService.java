package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.dto.PasswordResetConfirmRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetEmailVerifyRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetSmsSendRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetSmsVerifyRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetTokenResponse;
import kr.silverbridge.main.domain.auth.repository.RefreshTokenRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.AccessAction;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AccessLogService accessLogService;
    private final JavaMailSender mailSender;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;
    private final SmsService smsService;

    private static final long RESET_TOKEN_TTL    = 30L; // 재설정 토큰 유효 시간 (분)
    private static final long EMAIL_CODE_TTL     = 5L;  // 이메일 인증코드 유효 시간 (분)
    private static final long EMAIL_COOLDOWN_TTL = 1L;  // 이메일 재발송 대기 시간 (분)
    private static final int  EMAIL_MAX_ATTEMPTS = 5;   // 이메일 최대 오류 횟수
    private static final long SMS_CODE_TTL       = 5L;  // SMS 인증코드 유효 시간 (분)
    private static final long SMS_COOLDOWN_TTL   = 1L;  // SMS 재발송 대기 시간 (분)
    private static final int  SMS_MAX_ATTEMPTS   = 5;   // SMS 최대 오류 횟수

    // [이메일 방식 1단계] 비밀번호 재설정 인증코드 이메일 발송
    // 이메일로 사용자 조회 → 재발송 대기 확인 → 6자리 코드 생성 → 이메일 발송 → Redis 저장
    // 보안을 위해 이메일이 없거나 카카오 사용자여도 동일하게 200 반환 (가입 여부 노출 방지)
    @Transactional(readOnly = true)
    public void sendEmailCode(PasswordResetRequest request) {
        String email = request.getEmail();
        User user = userRepository.findByEmail(email).orElse(null);

        // 사용자가 없거나 카카오 사용자면 조용히 종료 (가입 여부 노출 방지)
        if (user == null || user.isSocialProvider()) {
            return;
        }

        // 1분 이내 재발송 차단
        if (Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.PW_EMAIL_COOLDOWN + email))) {
            throw new CustomException(ErrorCode.SMS_SEND_TOO_FREQUENT);
        }

        String code = generateCode();

        // 인증코드 저장 (재발송 시 기존 코드 및 오류 횟수 초기화)
        redisTemplate.opsForValue().set(RedisKeys.PW_EMAIL_VERIFY + email, code, EMAIL_CODE_TTL, TimeUnit.MINUTES);
        redisTemplate.delete(RedisKeys.PW_EMAIL_ATTEMPT + email);

        // 재발송 대기 설정 (1분)
        redisTemplate.opsForValue().set(RedisKeys.PW_EMAIL_COOLDOWN + email, "1", EMAIL_COOLDOWN_TTL, TimeUnit.MINUTES);

        sendCodeEmail(email, code);
        log.info("비밀번호 재설정 이메일 인증코드 발송 완료: {}", email);
    }

    // [이메일 방식 2단계] 인증코드 확인 후 재설정 토큰 발급
    // 코드 만료 확인 → 오류 횟수 확인 → 코드 일치 확인 → 재설정 토큰 발급 (30분 유효)
    public PasswordResetTokenResponse verifyEmailCodeAndIssueToken(PasswordResetEmailVerifyRequest request) {
        String email      = request.getEmail();
        String verifyKey  = RedisKeys.PW_EMAIL_VERIFY + email;
        String attemptKey = RedisKeys.PW_EMAIL_ATTEMPT + email;

        String savedCode = redisTemplate.opsForValue().get(verifyKey);

        if (savedCode == null) {
            throw new CustomException(ErrorCode.EXPIRED_SMS_CODE);
        }

        if (!savedCode.equals(request.getCode())) {
            // 오류 횟수 증가
            Long attempts = redisTemplate.opsForValue().increment(attemptKey);
            redisTemplate.expire(attemptKey, EMAIL_CODE_TTL, TimeUnit.MINUTES);

            // 5회 이상 오류 시 인증코드 즉시 무효화
            if (attempts != null && attempts >= EMAIL_MAX_ATTEMPTS) {
                redisTemplate.delete(verifyKey);
                redisTemplate.delete(attemptKey);
                throw new CustomException(ErrorCode.SMS_TOO_MANY_ATTEMPTS);
            }

            throw new CustomException(ErrorCode.INVALID_SMS_CODE);
        }

        // 인증 완료 — 인증코드 및 오류 횟수 삭제
        redisTemplate.delete(verifyKey);
        redisTemplate.delete(attemptKey);

        // 이메일로 사용자 조회 후 재설정 토큰 발급 (30분 유효)
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue()
                .set(RedisKeys.PW_RESET + token, user.getId(), RESET_TOKEN_TTL, TimeUnit.MINUTES);

        log.info("비밀번호 재설정 토큰 발급 완료 (이메일 방식): {}", email);
        return new PasswordResetTokenResponse(token);
    }

    // [SMS 방식 1단계] 비밀번호 재설정 인증코드 발송
    // 이름+전화번호로 사용자 조회 → 재발송 대기 확인 → 인증코드 생성 → SMS 발송 → 저장
    // 보안을 위해 사용자가 없거나 카카오 사용자여도 동일하게 200 반환 (가입 여부 노출 방지)
    @Transactional(readOnly = true)
    public void requestResetBySms(PasswordResetSmsSendRequest request) {
        String phone = request.getPhone();

        User user = userRepository.findAllByNameAndPhone(request.getName(), phone).stream()
                .filter(User::isLocalProvider)
                .findFirst()
                .orElse(null);

        // 사용자가 없으면 조용히 종료 (가입 여부 노출 방지)
        if (user == null) {
            return;
        }

        // 1분 이내 재발송 차단
        if (Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.PW_SMS_COOLDOWN + phone))) {
            throw new CustomException(ErrorCode.SMS_SEND_TOO_FREQUENT);
        }

        String code = generateCode();

        smsService.sendSms(phone, "[SilverBridge] 비밀번호 재설정 인증번호: " + code + "\n유효 시간: 5분");

        // 인증코드 저장 (재발송 시 기존 코드 및 오류 횟수 초기화)
        redisTemplate.opsForValue().set(RedisKeys.PW_SMS_VERIFY + phone, code, SMS_CODE_TTL, TimeUnit.MINUTES);
        redisTemplate.delete(RedisKeys.PW_SMS_ATTEMPT + phone);

        // 재발송 대기 설정 (1분)
        redisTemplate.opsForValue().set(RedisKeys.PW_SMS_COOLDOWN + phone, "1", SMS_COOLDOWN_TTL, TimeUnit.MINUTES);

        log.info("비밀번호 재설정 SMS 발송 완료: {}", phone);
    }

    // [SMS 방식 2단계] 인증코드 확인 후 재설정 토큰 발급
    // 코드 만료 확인 → 오류 횟수 확인 → 코드 일치 확인 → 재설정 토큰 발급
    public PasswordResetTokenResponse verifySmsAndIssueToken(PasswordResetSmsVerifyRequest request) {
        String phone      = request.getPhone();
        String verifyKey  = RedisKeys.PW_SMS_VERIFY + phone;
        String attemptKey = RedisKeys.PW_SMS_ATTEMPT + phone;

        String savedCode = redisTemplate.opsForValue().get(verifyKey);

        if (savedCode == null) {
            throw new CustomException(ErrorCode.EXPIRED_SMS_CODE);
        }

        if (!savedCode.equals(request.getCode())) {
            // 오류 횟수 증가
            Long attempts = redisTemplate.opsForValue().increment(attemptKey);
            redisTemplate.expire(attemptKey, SMS_CODE_TTL, TimeUnit.MINUTES);

            // 5회 이상 오류 시 인증코드 즉시 무효화
            if (attempts != null && attempts >= SMS_MAX_ATTEMPTS) {
                redisTemplate.delete(verifyKey);
                redisTemplate.delete(attemptKey);
                throw new CustomException(ErrorCode.SMS_TOO_MANY_ATTEMPTS);
            }

            throw new CustomException(ErrorCode.INVALID_SMS_CODE);
        }

        // 인증 완료 — 인증코드 및 오류 횟수 삭제
        redisTemplate.delete(verifyKey);
        redisTemplate.delete(attemptKey);

        // 전화번호로 사용자 조회 후 재설정 토큰 발급 (30분 유효)
        User user = userRepository.findByPhone(phone)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue()
                .set(RedisKeys.PW_RESET + token, user.getId(), RESET_TOKEN_TTL, TimeUnit.MINUTES);

        log.info("비밀번호 재설정 토큰 발급 완료 (SMS 방식): {}", phone);
        return new PasswordResetTokenResponse(token);
    }

    // [공통 마지막 단계] 새 비밀번호 설정
    // 재설정 토큰 확인 → 비밀번호 검증 → 변경 → 토큰 삭제 → 모든 기기 로그아웃 → 로그 기록
    @Transactional
    public void confirmReset(PasswordResetConfirmRequest request) {
        String key    = RedisKeys.PW_RESET + request.getToken();
        String userId = redisTemplate.opsForValue().get(key);

        // 저장된 토큰이 없으면 만료되거나 유효하지 않은 요청
        if (userId == null) {
            throw new CustomException(ErrorCode.INVALID_RESET_TOKEN);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 현재 비밀번호와 동일한 경우 차단
        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            throw new CustomException(ErrorCode.SAME_AS_CURRENT_PASSWORD);
        }

        // 비밀번호 변경
        user.updatePassword(passwordEncoder.encode(request.getNewPassword()));

        // 사용된 재설정 토큰 즉시 삭제 (재사용 방지)
        redisTemplate.delete(key);

        // 비밀번호 변경 후 모든 기기에서 자동 로그아웃
        refreshTokenRepository.deleteByUserId(userId);

        // 비밀번호 재설정 이력 기록
        accessLogService.log(userId, AccessAction.PASSWORD_RESET);
    }

    private void sendCodeEmail(String to, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("[SilverBridge] 비밀번호 재설정 인증코드 안내");
        message.setText(
                "비밀번호 재설정 인증코드: " + code + "\n\n" +
                "유효 시간: 5분\n\n" +
                "본인이 요청하지 않은 경우 이 메일을 무시하세요."
        );
        try {
            mailSender.send(message);
        } catch (MailException e) {
            log.error("이메일 발송 실패: {}", e.getMessage());
            // 발송 실패 시 저장된 코드 즉시 삭제 (미사용 코드 누적 방지)
            redisTemplate.delete(RedisKeys.PW_EMAIL_VERIFY + to);
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private String generateCode() {
        return String.format("%06d", new SecureRandom().nextInt(1_000_000));
    }
}

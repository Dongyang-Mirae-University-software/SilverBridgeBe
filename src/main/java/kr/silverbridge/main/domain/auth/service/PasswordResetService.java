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
import kr.silverbridge.main.global.util.MaskingUtil;
import kr.silverbridge.main.global.util.RedisKeys;
import kr.silverbridge.main.global.util.VerificationCodeValidator;
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
    private final SmsVerificationService smsVerificationService;
    private final VerificationCodeValidator verificationCodeValidator;

    /** 재설정 토큰 유효 시간 (분) — 인증 통과 후 새 비밀번호 입력까지의 여유 시간 */
    private static final long RESET_TOKEN_TTL_MINUTES = 30L;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String PASSWORD_RESET_SMS_TEMPLATE =
            "[SilverBridge] 비밀번호 재설정 인증번호: %s\n유효 시간: 5분";

    // [이메일 방식] 비밀번호 재설정 인증코드 이메일 발송
    // 이메일로 사용자 조회 → 쿨다운 확인 → 6자리 코드 생성 → 메일 발송 → Redis 저장(5분) → 오류 횟수 초기화 → 쿨다운 설정(1분)
    // 보안을 위해 사용자가 없거나 카카오 사용자여도 동일하게 200 반환 (가입 여부 노출 방지)
    @Transactional(readOnly = true)
    public void requestReset(PasswordResetRequest request) {
        User user = userRepository.findByEmail(request.getEmail()).orElse(null);

        // 사용자가 없거나 카카오 사용자면 조용히 종료 (가입 여부 노출 방지)
        if (user == null || user.isSocialProvider()) {
            return;
        }

        String email = user.getEmail();
        SmsKeyConfig config = SmsKeyConfig.PASSWORD_RESET_EMAIL;

        // 1분 이내 재발송 차단
        if (Boolean.TRUE.equals(redisTemplate.hasKey(config.cooldownKey(email)))) {
            throw new CustomException(ErrorCode.SMS_SEND_TOO_FREQUENT);
        }

        String code = generateCode();
        sendResetEmail(email, code);

        // 인증코드 저장 + 기존 오류 횟수 초기화
        redisTemplate.opsForValue()
                .set(config.verifyKey(email), code, SmsVerificationService.CODE_TTL_MINUTES, TimeUnit.MINUTES);
        redisTemplate.delete(config.attemptKey(email));

        // 재발송 대기 설정
        redisTemplate.opsForValue()
                .set(config.cooldownKey(email), "1", SmsVerificationService.COOLDOWN_TTL_MINUTES, TimeUnit.MINUTES);

        log.info("비밀번호 재설정 이메일 발송 완료: {}", MaskingUtil.maskEmail(email));
    }

    // [이메일 방식] 인증코드 확인 후 재설정 토큰 발급
    // 공통 검증 → 이메일로 사용자 조회 → UUID 재설정 토큰 발급(30분 유효)
    public PasswordResetTokenResponse verifyEmailToken(PasswordResetEmailVerifyRequest request) {
        String email = request.getEmail();
        SmsKeyConfig config = SmsKeyConfig.PASSWORD_RESET_EMAIL;

        verificationCodeValidator.verify(
                config.verifyKey(email),
                config.attemptKey(email),
                request.getCode(),
                SmsVerificationService.CODE_TTL_MINUTES,
                SmsVerificationService.MAX_ATTEMPTS
        );

        // 이메일로 사용자 조회 후 재설정 토큰 발급
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue()
                .set(RedisKeys.PW_RESET + token, user.getId(), RESET_TOKEN_TTL_MINUTES, TimeUnit.MINUTES);

        log.info("비밀번호 재설정 토큰 발급 완료: {}", MaskingUtil.maskEmail(email));
        return new PasswordResetTokenResponse(token);
    }

    // [SMS 방식] 비밀번호 재설정 인증코드 발송
    // 이름+전화번호로 사용자 조회 → 공통 SMS 로직에 위임 (쿨다운·코드저장·발송)
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

        smsVerificationService.sendCode(phone, SmsKeyConfig.PASSWORD_RESET, PASSWORD_RESET_SMS_TEMPLATE);
        log.info("비밀번호 재설정 SMS 발송 완료: {}", MaskingUtil.maskPhone(phone));
    }

    // [SMS 방식] 인증코드 확인 후 재설정 코드 발급
    // 공통 검증 → 전화번호로 사용자 조회 → 재설정 토큰 발급
    public PasswordResetTokenResponse verifySmsAndIssueToken(PasswordResetSmsVerifyRequest request) {
        String phone = request.getPhone();

        smsVerificationService.verifyCode(phone, SmsKeyConfig.PASSWORD_RESET, request.getCode());

        // 전화번호로 사용자 조회 후 재설정 코드 발급 (30분 유효)
        User user = userRepository.findByPhone(phone)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue()
                .set(RedisKeys.PW_RESET + token, user.getId(), RESET_TOKEN_TTL_MINUTES, TimeUnit.MINUTES);

        log.info("비밀번호 재설정 토큰 발급 완료: {}", MaskingUtil.maskPhone(phone));
        return new PasswordResetTokenResponse(token);
    }

    // 새 비밀번호 설정 (이메일/SMS 방식 공통)
    // 재설정 코드 확인 → 비밀번호 검증 → 변경 → 코드 삭제 → 모든 기기 로그아웃 → 로그 기록
    @Transactional
    public void confirmReset(PasswordResetConfirmRequest request) {
        String key    = RedisKeys.PW_RESET + request.getToken();
        String userId = redisTemplate.opsForValue().get(key);

        // 저장된 코드가 없으면 만료되거나 유효하지 않은 요청
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

        // 사용된 재설정 코드 즉시 삭제 (재사용 방지)
        redisTemplate.delete(key);

        // 비밀번호 변경 후 모든 기기에서 자동 로그아웃
        refreshTokenRepository.deleteByUserId(userId);

        // 비밀번호 재설정 이력 기록
        accessLogService.log(userId, AccessAction.PASSWORD_RESET);
    }

    private String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private void sendResetEmail(String to, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("[SilverBridge] 비밀번호 재설정 인증번호");
        message.setText(
                "비밀번호 재설정 인증번호: " + code + "\n\n" +
                "유효 시간: 5분\n\n" +
                "본인이 요청하지 않은 경우 이 메일을 무시하세요."
        );
        try {
            mailSender.send(message);
        } catch (MailException e) {
            log.error("이메일 발송 실패: {}", e.getMessage());
            // 호출자(requestReset)는 send 성공 후에야 Redis 에 저장하므로 정리할 키 없음
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

}
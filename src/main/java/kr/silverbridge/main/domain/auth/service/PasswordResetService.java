package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.dto.PasswordResetConfirmRequest;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private static final long RESET_TOKEN_TTL  = 30L; // 재설정 코드 유효 시간 (분)
    private static final long SMS_CODE_TTL     = 5L;  // 인증코드 유효 시간 (분)
    private static final long SMS_COOLDOWN_TTL = 1L;  // 재발송 대기 시간 (분)
    private static final int  SMS_MAX_ATTEMPTS = 5;   // 최대 오류 횟수

    // [이메일 방식] 비밀번호 재설정 이메일 발송
    // 이메일로 사용자 조회 → 재설정 코드 생성 → 저장(30분 유효) → 이메일 발송
    // 보안을 위해 이메일이 없거나 카카오 사용자여도 동일하게 200 반환 (가입 여부 노출 방지)
    @Transactional(readOnly = true)
    public void requestReset(PasswordResetRequest request) {
        User user = userRepository.findByEmail(request.getEmail()).orElse(null);

        // 사용자가 없거나 카카오 사용자면 조용히 종료 (가입 여부 노출 방지)
        if (user == null || user.isSocialProvider()) {
            return;
        }

        String token = UUID.randomUUID().toString();

        // 재설정 코드 임시 저장 (30분 유효)
        redisTemplate.opsForValue()
                .set(RedisKeys.PW_RESET + token, user.getId(), RESET_TOKEN_TTL, TimeUnit.MINUTES);

        sendResetEmail(user.getEmail(), token);
        log.info("비밀번호 재설정 이메일 발송 완료: {}", user.getEmail());
    }

    // [SMS 방식] 비밀번호 재설정 인증코드 발송
    // 이름+전화번호로 사용자 조회 → 재발송 대기 확인 → 인증코드 생성 → SMS 발송 → 저장
    // 보안을 위해 사용자가 없거나 카카오 사용자여도 동일하게 200 반환 (가입 여부 노출 방지)
    @Transactional(readOnly = true)
    public void requestResetBySms(PasswordResetSmsSendRequest request) {
        String phone = request.getPhone();

        User user = userRepository.findByNameAndPhone(request.getName(), phone).orElse(null);

        // 사용자가 없거나 카카오 사용자면 조용히 종료 (가입 여부 노출 방지)
        if (user == null || user.isSocialProvider()) {
            return;
        }

        // 1분 이내 재발송 차단
        if (Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.PW_SMS_COOLDOWN + phone))) {
            throw new CustomException(ErrorCode.SMS_SEND_TOO_FREQUENT);
        }

        String code = smsService.generateCode();

        smsService.sendSms(phone, "[SilverBridge] 비밀번호 재설정 인증번호: " + code + "\n유효 시간: 5분");

        // 인증코드 저장 (재발송 시 기존 코드 및 오류 횟수 초기화)
        redisTemplate.opsForValue().set(RedisKeys.PW_SMS_VERIFY + phone, code, SMS_CODE_TTL, TimeUnit.MINUTES);
        redisTemplate.delete(RedisKeys.PW_SMS_ATTEMPT + phone);

        // 재발송 대기 설정 (1분)
        redisTemplate.opsForValue().set(RedisKeys.PW_SMS_COOLDOWN + phone, "1", SMS_COOLDOWN_TTL, TimeUnit.MINUTES);

        log.info("비밀번호 재설정 SMS 발송 완료: {}", phone);
    }

    // [SMS 방식] 인증코드 확인 후 재설정 코드 발급
    // 코드 만료 확인 → 오류 횟수 확인 → 코드 일치 확인 → 재설정 코드 발급
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
            // 오류 횟수 만료 시간을 인증코드와 동일하게 설정
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

        // 전화번호로 사용자 조회 후 재설정 코드 발급 (30분 유효)
        User user = userRepository.findByPhone(phone)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue()
                .set(RedisKeys.PW_RESET + token, user.getId(), RESET_TOKEN_TTL, TimeUnit.MINUTES);

        log.info("비밀번호 재설정 코드 발급 완료: {}", phone);
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

        // 최근 사용한 비밀번호 2개와 중복 검사
        if (user.isPasswordRecentlyUsed(request.getNewPassword(), passwordEncoder)) {
            throw new CustomException(ErrorCode.PASSWORD_RECENTLY_USED);
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

    private void sendResetEmail(String to, String token) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("[SilverBridge] 비밀번호 재설정 안내");
        message.setText(
                "비밀번호 재설정 코드: " + token + "\n\n" +
                "유효 시간: 30분\n\n" +
                "본인이 요청하지 않은 경우 이 메일을 무시하세요."
        );
        mailSender.send(message);
    }

}

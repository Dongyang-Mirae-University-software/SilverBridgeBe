package kr.silverbridge.main.domain.auth.service;

import com.solapi.sdk.SolapiClient;
import com.solapi.sdk.message.exception.SolapiEmptyResponseException;
import com.solapi.sdk.message.exception.SolapiMessageNotReceivedException;
import com.solapi.sdk.message.exception.SolapiUnknownException;
import com.solapi.sdk.message.model.Message;
import com.solapi.sdk.message.service.DefaultMessageService;
import kr.silverbridge.main.domain.auth.dto.PasswordResetConfirmRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetSmsSendRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetSmsVerifyRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetTokenResponse;
import kr.silverbridge.main.domain.auth.entity.AccessLog;
import kr.silverbridge.main.domain.auth.repository.AccessLogRepository;
import kr.silverbridge.main.domain.auth.repository.RefreshTokenRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    private final AccessLogRepository accessLogRepository;
    private final JavaMailSender mailSender;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;

    @Value("${solapi.api-key}")
    private String apiKey;

    @Value("${solapi.api-secret}")
    private String apiSecret;

    @Value("${solapi.sender-phone}")
    private String senderPhone;

    private static final long RESET_TOKEN_TTL   = 30L;   // 재설정 토큰 TTL (분)
    private static final long SMS_CODE_TTL      = 5L;    // 인증코드 TTL (분)
    private static final long SMS_COOLDOWN_TTL  = 1L;    // 재발송 쿨다운 (분)
    private static final int  SMS_MAX_ATTEMPTS  = 5;     // 최대 틀림 횟수

    private static final String RESET_PREFIX        = "password:reset:";
    private static final String SMS_VERIFY_PREFIX   = "password:sms:verify:";
    private static final String SMS_COOLDOWN_PREFIX = "password:sms:cooldown:";
    private static final String SMS_ATTEMPT_PREFIX  = "password:sms:attempt:";

    // [이메일 방식] 비밀번호 재설정 요청
    // 이메일 확인 → UUID 토큰 생성 → Redis 저장(TTL 30분) → 이메일 발송
    // 존재하지 않는 이메일이어도 200 반환 — 이메일 존재 여부 노출 방지
    @Transactional(readOnly = true)
    public void requestReset(PasswordResetRequest request) {
        User user = userRepository.findByEmail(request.getEmail()).orElse(null);

        // 사용자가 없거나 카카오 사용자면 조용히 종료 (존재 여부 노출 방지)
        if (user == null || user.getProvider() != Provider.LOCAL) {
            return;
        }

        String token = UUID.randomUUID().toString();

        // Redis에 토큰 → userId 매핑 저장
        redisTemplate.opsForValue()
                .set(RESET_PREFIX + token, user.getId(), RESET_TOKEN_TTL, TimeUnit.MINUTES);

        sendResetEmail(user.getEmail(), token);
        log.info("비밀번호 재설정 이메일 발송 완료: {}", user.getEmail());
    }

    // [SMS 방식] 비밀번호 재설정 SMS 발송
    // 이름+전화번호로 사용자 확인 → 쿨다운 확인 → 인증코드 생성 → SMS 발송 → Redis 저장
    // 존재하지 않는 사용자여도 200 반환 — 사용자 존재 여부 노출 방지
    @Transactional(readOnly = true)
    public void requestResetBySms(PasswordResetSmsSendRequest request) {
        String phone = request.getPhone();

        User user = userRepository.findByNameAndPhone(request.getName(), phone).orElse(null);

        // 사용자가 없거나 카카오 사용자면 조용히 종료 (존재 여부 노출 방지)
        if (user == null || user.getProvider() != Provider.LOCAL) {
            return;
        }

        // 1분 이내 재발송 차단
        if (Boolean.TRUE.equals(redisTemplate.hasKey(SMS_COOLDOWN_PREFIX + phone))) {
            throw new CustomException(ErrorCode.SMS_SEND_TOO_FREQUENT);
        }

        String code = generateCode();

        DefaultMessageService messageService =
                SolapiClient.INSTANCE.createInstance(apiKey, apiSecret);

        Message message = new Message();
        message.setFrom(senderPhone);
        message.setTo(phone);
        message.setText("[SilverBridge] 비밀번호 재설정 인증번호: " + code + "\n유효 시간: 5분");

        try {
            messageService.send(message);
        } catch (SolapiMessageNotReceivedException e) {
            log.error("SMS 발송 실패: {}", e.getFailedMessageList());
            throw new CustomException(ErrorCode.SMS_SEND_FAILED);
        } catch (SolapiEmptyResponseException | SolapiUnknownException e) {
            log.error("SMS 오류: {}", e.getMessage());
            throw new CustomException(ErrorCode.SMS_SEND_FAILED);
        }

        // 인증 코드 저장 (재발송 시 기존 코드 및 시도 횟수 초기화)
        redisTemplate.opsForValue().set(SMS_VERIFY_PREFIX + phone, code, SMS_CODE_TTL, TimeUnit.MINUTES);
        redisTemplate.delete(SMS_ATTEMPT_PREFIX + phone);

        // 재발송 쿨다운 설정 (1분)
        redisTemplate.opsForValue().set(SMS_COOLDOWN_PREFIX + phone, "1", SMS_COOLDOWN_TTL, TimeUnit.MINUTES);

        log.info("비밀번호 재설정 SMS 발송 완료: {}", phone);
    }

    // [SMS 방식] 인증코드 검증 → 재설정 토큰 발급
    // 코드 만료 확인 → 시도 횟수 확인 → 코드 일치 확인 → 재설정 토큰 반환
    public PasswordResetTokenResponse verifySmsAndIssueToken(PasswordResetSmsVerifyRequest request) {
        String phone      = request.getPhone();
        String verifyKey  = SMS_VERIFY_PREFIX + phone;
        String attemptKey = SMS_ATTEMPT_PREFIX + phone;

        String savedCode = redisTemplate.opsForValue().get(verifyKey);

        if (savedCode == null) {
            throw new CustomException(ErrorCode.EXPIRED_SMS_CODE);
        }

        if (!savedCode.equals(request.getCode())) {
            Long attempts = redisTemplate.opsForValue().increment(attemptKey);
            redisTemplate.expire(attemptKey, SMS_CODE_TTL, TimeUnit.MINUTES);

            // 5회 초과 시 코드 즉시 무효화
            if (attempts != null && attempts >= SMS_MAX_ATTEMPTS) {
                redisTemplate.delete(verifyKey);
                redisTemplate.delete(attemptKey);
                throw new CustomException(ErrorCode.SMS_TOO_MANY_ATTEMPTS);
            }

            throw new CustomException(ErrorCode.INVALID_SMS_CODE);
        }

        // 인증 완료 — 코드 및 시도 횟수 삭제
        redisTemplate.delete(verifyKey);
        redisTemplate.delete(attemptKey);

        // 재설정 토큰 발급 (30분 유효)
        // 전화번호로 사용자 조회 → userId를 토큰에 매핑
        User user = userRepository.findByPhone(phone)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue()
                .set(RESET_PREFIX + token, user.getId(), RESET_TOKEN_TTL, TimeUnit.MINUTES);

        log.info("비밀번호 재설정 토큰 발급 완료: {}", phone);
        return new PasswordResetTokenResponse(token);
    }

    // 비밀번호 재설정 확인 (이메일/SMS 방식 공통)
    // 토큰으로 userId 조회 → 비밀번호 변경 → Redis 삭제 → Refresh Token 전체 삭제 → 로그 기록
    @Transactional
    public void confirmReset(PasswordResetConfirmRequest request) {
        String key    = RESET_PREFIX + request.getToken();
        String userId = redisTemplate.opsForValue().get(key);

        // Redis에 키가 없으면 만료되거나 존재하지 않는 토큰
        if (userId == null) {
            throw new CustomException(ErrorCode.INVALID_RESET_TOKEN);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 현재 비밀번호와 동일한 경우 차단
        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            throw new CustomException(ErrorCode.SAME_AS_CURRENT_PASSWORD);
        }

        // 이전 비밀번호 2개와 중복 검사
        checkPasswordHistory(user, request.getNewPassword());

        // 비밀번호 변경 (이력 자동 보관)
        user.updatePassword(passwordEncoder.encode(request.getNewPassword()));

        // 사용된 토큰 즉시 삭제 — 재사용 방지
        redisTemplate.delete(key);

        // 모든 디바이스 Refresh Token 삭제 — 비밀번호 변경 후 재로그인 강제
        refreshTokenRepository.deleteByUserId(userId);

        // 비밀번호 재설정 로그 기록
        accessLogRepository.save(AccessLog.builder()
                .userId(userId)
                .action("PASSWORD_RESET")
                .build());
    }

    // 이전 비밀번호 2개와 중복 여부 검사
    private void checkPasswordHistory(User user, String newPassword) {
        if (user.getPrevPassword1() != null && passwordEncoder.matches(newPassword, user.getPrevPassword1())) {
            throw new CustomException(ErrorCode.PASSWORD_RECENTLY_USED);
        }
        if (user.getPrevPassword2() != null && passwordEncoder.matches(newPassword, user.getPrevPassword2())) {
            throw new CustomException(ErrorCode.PASSWORD_RECENTLY_USED);
        }
    }

    private void sendResetEmail(String to, String token) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("[SilverBridge] 비밀번호 재설정 안내");
        message.setText(
                "비밀번호 재설정 토큰: " + token + "\n\n" +
                "유효 시간: 30분\n\n" +
                "본인이 요청하지 않은 경우 이 메일을 무시하세요."
        );
        mailSender.send(message);
    }

    private String generateCode() {
        return String.format("%06d", new SecureRandom().nextInt(1_000_000));
    }
}

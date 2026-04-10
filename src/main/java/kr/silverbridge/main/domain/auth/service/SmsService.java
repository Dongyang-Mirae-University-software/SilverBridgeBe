package kr.silverbridge.main.domain.auth.service;

import com.solapi.sdk.SolapiClient;
import com.solapi.sdk.message.exception.SolapiEmptyResponseException;
import com.solapi.sdk.message.exception.SolapiMessageNotReceivedException;
import com.solapi.sdk.message.exception.SolapiUnknownException;
import com.solapi.sdk.message.model.Message;
import com.solapi.sdk.message.service.DefaultMessageService;
import kr.silverbridge.main.domain.auth.dto.SmsSendRequest;
import kr.silverbridge.main.domain.auth.dto.SmsVerifyRequest;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsService {

    private final StringRedisTemplate redisTemplate;

    @Value("${solapi.api-key}")
    private String apiKey;

    @Value("${solapi.api-secret}")
    private String apiSecret;

    @Value("${solapi.sender-phone}")
    private String senderPhone;

    private static final long   CODE_TTL       = 5L;   // 인증코드 TTL (분)
    private static final long   VERIFIED_TTL   = 10L;  // 인증완료 TTL (분)
    private static final long   COOLDOWN_TTL   = 1L;   // 재발송 쿨다운 (분)
    private static final int    MAX_ATTEMPTS   = 5;    // 최대 틀림 횟수

    private static final String VERIFY_PREFIX   = "sms:verify:";
    private static final String VERIFIED_PREFIX = "sms:verified:";
    private static final String COOLDOWN_PREFIX = "sms:cooldown:";
    private static final String ATTEMPT_PREFIX  = "sms:attempt:";

    // SMS 인증 코드 발송
    // 쿨다운 확인(1분) → 코드 생성 → Redis 저장(TTL 5분) → SMS 발송 → 쿨다운 설정
    public void sendVerificationCode(SmsSendRequest request) {
        String phone = request.getPhone();

        // 1분 이내 재발송 차단
        if (Boolean.TRUE.equals(redisTemplate.hasKey(COOLDOWN_PREFIX + phone))) {
            throw new CustomException(ErrorCode.SMS_SEND_TOO_FREQUENT);
        }

        String code = generateCode();

        DefaultMessageService messageService =
                SolapiClient.INSTANCE.createInstance(apiKey, apiSecret);

        Message message = new Message();
        message.setFrom(senderPhone);
        message.setTo(phone);
        message.setText("[SilverBridge] 인증번호: " + code + "\n유효 시간: 5분");

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
        redisTemplate.opsForValue().set(VERIFY_PREFIX + phone, code, CODE_TTL, TimeUnit.MINUTES);
        redisTemplate.delete(ATTEMPT_PREFIX + phone);

        // 재발송 쿨다운 설정 (1분)
        redisTemplate.opsForValue().set(COOLDOWN_PREFIX + phone, "1", COOLDOWN_TTL, TimeUnit.MINUTES);

        log.info("SMS 인증 코드 발송 완료: {}", phone);
    }

    // SMS 인증 코드 검증
    // 코드 만료 확인 → 시도 횟수 확인 → 코드 일치 확인 → 인증 완료 표시
    public void verifyCode(SmsVerifyRequest request) {
        String phone = request.getPhone();
        String verifyKey  = VERIFY_PREFIX + phone;
        String attemptKey = ATTEMPT_PREFIX + phone;

        String savedCode = redisTemplate.opsForValue().get(verifyKey);

        if (savedCode == null) {
            throw new CustomException(ErrorCode.EXPIRED_SMS_CODE);
        }

        if (!savedCode.equals(request.getCode())) {
            // 틀린 횟수 증가
            Long attempts = redisTemplate.opsForValue().increment(attemptKey);
            // 시도 횟수 TTL을 인증코드 TTL과 동기화
            redisTemplate.expire(attemptKey, CODE_TTL, TimeUnit.MINUTES);

            // 5회 초과 시 코드 즉시 무효화
            if (attempts != null && attempts >= MAX_ATTEMPTS) {
                redisTemplate.delete(verifyKey);
                redisTemplate.delete(attemptKey);
                throw new CustomException(ErrorCode.SMS_TOO_MANY_ATTEMPTS);
            }

            throw new CustomException(ErrorCode.INVALID_SMS_CODE);
        }

        // 인증 완료 — 코드 및 시도 횟수 삭제, 완료 표시 저장
        redisTemplate.delete(verifyKey);
        redisTemplate.delete(attemptKey);
        redisTemplate.opsForValue()
                .set(VERIFIED_PREFIX + phone, "true", VERIFIED_TTL, TimeUnit.MINUTES);

        log.info("SMS 인증 완료: {}", phone);
    }

    private String generateCode() {
        return String.format("%06d", new SecureRandom().nextInt(1_000_000));
    }
}

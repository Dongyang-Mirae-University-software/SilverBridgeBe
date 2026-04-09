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

    private static final long CODE_TTL     = 5L;   // 인증코드 TTL (분)
    private static final long VERIFIED_TTL = 10L;  // 인증완료 TTL (분)
    private static final String VERIFY_PREFIX   = "sms:verify:";
    private static final String VERIFIED_PREFIX = "sms:verified:";

    // SMS 인증 코드 발송
    // 6자리 코드 생성 → Redis 저장(TTL 5분) → Solapi로 SMS 발송
    public void sendVerificationCode(SmsSendRequest request) {
        String phone = request.getPhone();
        String code  = generateCode();

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

        // Redis에 인증 코드 저장 (재발송 시 덮어씀)
        redisTemplate.opsForValue()
                .set(VERIFY_PREFIX + phone, code, CODE_TTL, TimeUnit.MINUTES);

        log.info("SMS 인증 코드 발송 완료: {}", phone);
    }

    // SMS 인증 코드 검증
    // Redis 코드 확인 → 일치 시 인증 완료 표시(TTL 10분)
    public void verifyCode(SmsVerifyRequest request) {
        String phone = request.getPhone();
        String key   = VERIFY_PREFIX + phone;

        String savedCode = redisTemplate.opsForValue().get(key);

        if (savedCode == null) {
            throw new CustomException(ErrorCode.EXPIRED_SMS_CODE);
        }

        if (!savedCode.equals(request.getCode())) {
            throw new CustomException(ErrorCode.INVALID_SMS_CODE);
        }

        // 인증 코드 삭제 후 완료 표시 저장
        redisTemplate.delete(key);
        redisTemplate.opsForValue()
                .set(VERIFIED_PREFIX + phone, "true", VERIFIED_TTL, TimeUnit.MINUTES);

        log.info("SMS 인증 완료: {}", phone);
    }

    private String generateCode() {
        return String.format("%06d", new SecureRandom().nextInt(1_000_000));
    }
}

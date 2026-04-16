package kr.silverbridge.main.domain.auth.service;

import com.solapi.sdk.SolapiClient;
import com.solapi.sdk.message.exception.SolapiEmptyResponseException;
import com.solapi.sdk.message.exception.SolapiMessageNotReceivedException;
import com.solapi.sdk.message.exception.SolapiUnknownException;
import com.solapi.sdk.message.model.Message;
import com.solapi.sdk.message.service.DefaultMessageService;
import kr.silverbridge.main.domain.auth.dto.SmsSendRequest;
import kr.silverbridge.main.domain.auth.dto.SmsVerifyRequest;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.util.MaskingUtil;
import kr.silverbridge.main.global.util.RedisKeys;
import kr.silverbridge.main.global.util.VerificationCodeValidator;
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

    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final VerificationCodeValidator verificationCodeValidator;

    @Value("${solapi.api-key}")
    private String apiKey;

    @Value("${solapi.api-secret}")
    private String apiSecret;

    @Value("${solapi.sender-phone}")
    private String senderPhone;

    private static final long CODE_TTL     = 5L;  // 인증코드 유효 시간 (분)
    private static final long VERIFIED_TTL = 10L; // 인증 완료 상태 유지 시간 (분)
    private static final long COOLDOWN_TTL = 1L;  // 재발송 대기 시간 (분)
    private static final int  MAX_ATTEMPTS = 5;   // 최대 오류 횟수

    // 인증코드 발송
    // 재발송 대기 확인(1분) → 인증코드 생성 → SMS 발송 → 저장(5분 유효) → 재발송 대기 설정
    public void sendVerificationCode(SmsSendRequest request) {
        String phone = request.getPhone();

        // 이미 가입된 전화번호인지 확인 (SMS 낭비 방지)
        if (userRepository.existsByPhone(phone)) {
            throw new CustomException(ErrorCode.PHONE_ALREADY_EXISTS);
        }

        // 1분 이내 재발송 차단
        if (Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.SMS_COOLDOWN + phone))) {
            throw new CustomException(ErrorCode.SMS_SEND_TOO_FREQUENT);
        }

        String code = generateCode();

        sendSms(phone, "[SilverBridge] 인증번호: " + code + "\n유효 시간: 5분");

        // 인증코드 저장 (재발송 시 기존 코드 및 오류 횟수 초기화)
        redisTemplate.opsForValue().set(RedisKeys.SMS_VERIFY + phone, code, CODE_TTL, TimeUnit.MINUTES);
        redisTemplate.delete(RedisKeys.SMS_ATTEMPT + phone);

        // 재발송 대기 설정 (1분)
        redisTemplate.opsForValue().set(RedisKeys.SMS_COOLDOWN + phone, "1", COOLDOWN_TTL, TimeUnit.MINUTES);

        log.info("SMS 인증코드 발송 완료: {}", MaskingUtil.maskPhone(phone));
    }

    // 인증코드 확인
    // 코드 만료 확인 → 오류 횟수 확인 → 코드 일치 확인 → 인증 완료 처리
    public void verifyCode(SmsVerifyRequest request) {
        String phone = request.getPhone();

        verificationCodeValidator.verify(
                RedisKeys.SMS_VERIFY + phone,
                RedisKeys.SMS_ATTEMPT + phone,
                request.getCode(),
                CODE_TTL,
                MAX_ATTEMPTS
        );

        // 인증 완료 상태 저장 (10분 유효)
        redisTemplate.opsForValue()
                .set(RedisKeys.SMS_VERIFIED + phone, "true", VERIFIED_TTL, TimeUnit.MINUTES);

        log.info("SMS 인증 완료: {}", MaskingUtil.maskPhone(phone));
    }

    // Solapi를 통해 SMS 발송 (공통 발송 처리)
    void sendSms(String phone, String text) {
        DefaultMessageService messageService =
                SolapiClient.INSTANCE.createInstance(apiKey, apiSecret);

        Message message = new Message();
        message.setFrom(senderPhone);
        message.setTo(phone);
        message.setText(text);

        try {
            messageService.send(message);
        } catch (SolapiMessageNotReceivedException e) {
            log.error("SMS 발송 실패: {}", e.getFailedMessageList());
            throw new CustomException(ErrorCode.SMS_SEND_FAILED);
        } catch (SolapiEmptyResponseException | SolapiUnknownException e) {
            log.error("SMS 오류: {}", e.getMessage());
            throw new CustomException(ErrorCode.SMS_SEND_FAILED);
        }
    }

    String generateCode() {
        return String.format("%06d", new SecureRandom().nextInt(1_000_000));
    }
}

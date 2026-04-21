package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.dto.SmsSendRequest;
import kr.silverbridge.main.domain.auth.dto.SmsVerifyRequest;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.util.MaskingUtil;
import kr.silverbridge.main.global.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 회원가입용 SMS 인증 서비스
 * 공통 SMS 로직은 {@link SmsVerificationService}에 위임하고, 여기서는 회원가입 고유 정책만 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsService {

    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final SmsVerificationService smsVerificationService;

    /** 인증 완료 상태 유지 시간 (분) — 회원가입 흐름에서 후속 가입 요청 단계까지 유효해야 함 */
    private static final long VERIFIED_TTL_MINUTES = 10L;

    private static final String SIGNUP_MESSAGE_TEMPLATE =
            "[SilverBridge] 인증번호: %s\n유효 시간: 5분";

    /** 회원가입 SMS 인증코드 발송 — 이미 가입된 번호는 차단 후 공통 발송 로직으로 위임 */
    public void sendVerificationCode(SmsSendRequest request) {
        String phone = request.getPhone();

        // 이미 가입된 전화번호인지 확인 (SMS 낭비 방지)
        if (userRepository.existsByPhone(phone)) {
            throw new CustomException(ErrorCode.PHONE_ALREADY_EXISTS);
        }

        smsVerificationService.sendCode(phone, SmsKeyConfig.SIGNUP, SIGNUP_MESSAGE_TEMPLATE);
        log.info("SMS 인증코드 발송 완료: {}", MaskingUtil.maskPhone(phone));
    }

    /** 회원가입 SMS 인증코드 확인 — 성공 시 후속 가입 단계에서 사용할 "인증 완료" 상태 저장 */
    public void verifyCode(SmsVerifyRequest request) {
        String phone = request.getPhone();

        smsVerificationService.verifyCode(phone, SmsKeyConfig.SIGNUP, request.getCode());

        // 인증 완료 상태 저장 (10분 유효) — 회원가입 요청에서 검증용
        redisTemplate.opsForValue()
                .set(RedisKeys.SMS_VERIFIED + phone, "true", VERIFIED_TTL_MINUTES, TimeUnit.MINUTES);

        log.info("SMS 인증 완료: {}", MaskingUtil.maskPhone(phone));
    }
}

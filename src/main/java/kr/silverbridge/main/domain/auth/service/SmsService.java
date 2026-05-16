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

import java.util.UUID;
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

        smsVerificationService.sendCode(phone, VerificationKeyConfig.SIGNUP, SIGNUP_MESSAGE_TEMPLATE);
        log.info("SMS 인증코드 발송 완료: {}", MaskingUtil.maskPhone(phone));
    }

    /**
     * 회원가입 SMS 인증코드 확인 — 성공 시 nonce(UUID)를 발급해 Redis에 저장하고 반환한다.
     * 회원가입·전화번호 변경 요청에서 동일 nonce를 함께 보내야 인증을 인정한다(H-5).
     * 단순 phone 키만으로 인정하던 기존 정책은 같은 phone에 대해 다른 사용자가 인증 우회 가입할 여지가 있었다.
     */
    public String verifyCode(SmsVerifyRequest request) {
        String phone = request.getPhone();

        smsVerificationService.verifyCode(phone, VerificationKeyConfig.SIGNUP, request.getCode());

        // 인증 세션 식별자 발급 (10분 유효) — 회원가입 요청에서 nonce 일치 검증용
        String nonce = UUID.randomUUID().toString();
        redisTemplate.opsForValue()
                .set(RedisKeys.SMS_VERIFIED + phone, nonce, VERIFIED_TTL_MINUTES, TimeUnit.MINUTES);

        log.info("SMS 인증 완료: {}", MaskingUtil.maskPhone(phone));
        return nonce;
    }

    /**
     * 후속 요청(회원가입·프로필 수정 등)에서 SMS 인증 nonce 일치를 검증하고 키를 소비한다.
     * - 인증 미완료/만료/nonce 누락 → SMS_NOT_VERIFIED
     * - 호출 성공 시 SMS_VERIFIED 키 즉시 삭제 — 동일 nonce 재사용 방지
     */
    public void consumeVerification(String phone, String providedNonce) {
        if (providedNonce == null || providedNonce.isBlank()) {
            throw new CustomException(ErrorCode.SMS_NOT_VERIFIED);
        }
        String savedNonce = redisTemplate.opsForValue().get(RedisKeys.SMS_VERIFIED + phone);
        if (savedNonce == null || !savedNonce.equals(providedNonce)) {
            throw new CustomException(ErrorCode.SMS_NOT_VERIFIED);
        }
        redisTemplate.delete(RedisKeys.SMS_VERIFIED + phone);
    }
}

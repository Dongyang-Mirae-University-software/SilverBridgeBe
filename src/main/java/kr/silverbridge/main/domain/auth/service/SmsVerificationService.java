package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.global.util.VerificationCodeValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/**
 * SMS 인증코드 공통 발송/검증 서비스
 * 회원가입, 비밀번호 재설정 등 모든 SMS 인증 흐름의 공통 로직을 담당한다.
 * 호출자는 {@link VerificationKeyConfig}로 Redis 키 네임스페이스만 지정하면 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsVerificationService {

    private final StringRedisTemplate redisTemplate;
    private final VerificationCodeValidator verificationCodeValidator;
    private final SmsSender smsSender;

    /** 인증코드 유효 시간 (분) */
    public static final long CODE_TTL_MINUTES = 5L;
    /** 인증코드 유효 시간 (초) — 프론트 카운트다운(CodeSentResponse) 노출용 */
    public static final long CODE_TTL_SECONDS = CODE_TTL_MINUTES * 60;
    /** 최대 오류 허용 횟수 */
    public static final int MAX_ATTEMPTS = 5;

    /**
     * 인증코드 발송 공통 로직
     * 코드 생성 → SMS 발송 → 저장(5분) → 오류 횟수 초기화
     * <p>
     * 재발송 쿨다운은 두지 않는다. 인증요청을 잘못 눌렀을 때 즉시 다시 받을 수 있어야 하므로
     * 발송 빈도 방어는 컨트롤러단 IP RateLimit({@code RateLimitService})에만 의존한다.
     *
     * @param phone            수신 전화번호
     * @param config           Redis 키 설정 (흐름별 분리)
     * @param messageTemplate  SMS 본문 템플릿 ({@code %s} 위치에 인증코드 삽입)
     */
    public void sendCode(String phone, VerificationKeyConfig config, String messageTemplate) {
        String code = generateCode();
        smsSender.send(phone, String.format(messageTemplate, code));

        // 인증코드 저장 + 기존 오류 횟수 초기화 (기존 코드가 있으면 새 코드로 교체)
        redisTemplate.opsForValue()
                .set(config.verifyKey(phone), code, CODE_TTL_MINUTES, TimeUnit.MINUTES);
        redisTemplate.delete(config.attemptKey(phone));
    }

    /**
     * 인증코드 검증 공통 로직
     * 성공 시 인증코드·오류 횟수 모두 삭제되고, 실패 시 오류 횟수 증가 및 최대치 초과 시 즉시 무효화
     */
    public void verifyCode(String phone, VerificationKeyConfig config, String inputCode) {
        verificationCodeValidator.verify(
                config.verifyKey(phone),
                config.attemptKey(phone),
                inputCode,
                CODE_TTL_MINUTES,
                MAX_ATTEMPTS
        );
    }

    private String generateCode() {
        return String.format("%06d", new SecureRandom().nextInt(1_000_000));
    }
}

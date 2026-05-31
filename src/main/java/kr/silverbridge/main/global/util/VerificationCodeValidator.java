package kr.silverbridge.main.global.util;

import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * SMS/이메일 인증코드 검증 공통 유틸리티
 * 코드 만료 확인 → 일치 확인 → 오류 횟수 관리 → 성공 시 키 삭제
 */
@Component
@RequiredArgsConstructor
public class VerificationCodeValidator {

    private final StringRedisTemplate redisTemplate;
    private final RedisCounter redisCounter;

    /**
     * 인증코드를 검증하고, 성공 시 관련 Redis 키를 삭제한다.
     *
     * @param verifyKey      인증코드가 저장된 Redis 키
     * @param attemptKey     오류 횟수가 저장된 Redis 키
     * @param inputCode      사용자가 입력한 인증코드
     * @param codeTtlMinutes 오류 횟수 만료 시간 (분, 인증코드 TTL과 동일하게 설정)
     * @param maxAttempts    최대 오류 허용 횟수 (초과 시 인증코드 즉시 무효화)
     */
    public void verify(String verifyKey, String attemptKey, String inputCode,
                       long codeTtlMinutes, int maxAttempts) {
        String savedCode = redisTemplate.opsForValue().get(verifyKey);

        // 인증코드가 없으면 만료된 것
        if (savedCode == null) {
            throw new CustomException(ErrorCode.EXPIRED_SMS_CODE);
        }

        if (!codesMatch(savedCode, inputCode)) {
            // 오류 횟수 증가 + 최초 증가 시 TTL(인증코드와 동일) 설정을 원자적으로 (L-2)
            long attempts = redisCounter.incrementWithTtl(attemptKey, codeTtlMinutes * 60);

            // 최대 오류 횟수 초과 시 인증코드 즉시 무효화
            if (attempts >= maxAttempts) {
                redisTemplate.delete(verifyKey);
                redisTemplate.delete(attemptKey);
                throw new CustomException(ErrorCode.SMS_TOO_MANY_ATTEMPTS);
            }

            throw new CustomException(ErrorCode.INVALID_SMS_CODE);
        }

        // 인증 성공 — 인증코드 및 오류 횟수 삭제
        redisTemplate.delete(verifyKey);
        redisTemplate.delete(attemptKey);
    }

    /**
     * 인증코드를 검증하되 <b>성공해도 코드를 소비(삭제)하지 않는다.</b>
     * 비밀번호 재설정처럼 "확인(pre-check) → 이후 같은 6자리 코드로 최종 처리" 흐름의
     * pre-check 단계에서 사용한다. 실패 시 오류 횟수 증가·최대치 초과 무효화는 동일하게 동작한다.
     *
     * @see #verify(String, String, String, long, int)
     */
    public void verifyWithoutConsume(String verifyKey, String attemptKey, String inputCode,
                                     long codeTtlMinutes, int maxAttempts) {
        String savedCode = redisTemplate.opsForValue().get(verifyKey);

        if (savedCode == null) {
            throw new CustomException(ErrorCode.EXPIRED_SMS_CODE);
        }

        if (!codesMatch(savedCode, inputCode)) {
            long attempts = redisCounter.incrementWithTtl(attemptKey, codeTtlMinutes * 60);
            if (attempts >= maxAttempts) {
                redisTemplate.delete(verifyKey);
                redisTemplate.delete(attemptKey);
                throw new CustomException(ErrorCode.SMS_TOO_MANY_ATTEMPTS);
            }
            throw new CustomException(ErrorCode.INVALID_SMS_CODE);
        }
        // 성공 — 코드 유지(최종 reset 단계에서 같은 코드로 재검증·소비)
    }

    /**
     * 인증코드와 오류 횟수 키를 소비(삭제)한다.
     * <p>
     * {@link #verifyWithoutConsume}로 검증한 흐름에서 <b>모든 비즈니스 검증·처리가 성공한 뒤
     * 마지막 단계</b>에 호출해 1회용 소비를 보장한다. 검증과 소비를 분리해, 중간 단계(예: 사용자 조회·
     * 정책 위반)가 실패해도 코드가 비가역적으로 소모되지 않게 한다. (Redis 삭제는 {@code @Transactional}
     * 롤백 대상이 아니므로 "검증 후 마지막 소비" 순서가 중요 — 회원가입 nonce 소비와 동일 원칙)
     */
    public void consume(String verifyKey, String attemptKey) {
        redisTemplate.delete(verifyKey);
        redisTemplate.delete(attemptKey);
    }

    /**
     * 인증코드 일치 여부를 상수 시간으로 비교한다 (A-L1).
     * {@code String.equals}는 첫 불일치 문자에서 단락(short-circuit)되어 미세한 타이밍 차이를 만든다.
     * 6자리 코드는 MAX_ATTEMPTS=5로 이미 제한적이지만, 비밀 비교는 일관되게 상수 시간으로 처리한다.
     */
    private boolean codesMatch(String savedCode, String inputCode) {
        if (inputCode == null) {
            return false;
        }
        return MessageDigest.isEqual(
                savedCode.getBytes(StandardCharsets.UTF_8),
                inputCode.getBytes(StandardCharsets.UTF_8));
    }
}
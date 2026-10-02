package kr.silverbridge.main.global.util;

import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * SMS/이메일 인증코드 검증 공통 유틸리티
 * 코드 만료 확인 → 시도 횟수 예약 → 일치 확인 → 성공 시 키 삭제(소비형) 또는 예약 환불(비소비형)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationCodeValidator {

    private final StringRedisTemplate redisTemplate;
    private final RedisCounter redisCounter;

    /**
     * 시도 1회를 되돌린다 - 키가 있고 0보다 클 때만 DECR(TTL 유지). 그 사이 재발송으로 키가 지워졌으면
     * 아무것도 하지 않는다(음수·TTL 없는 키를 만들지 않게).
     */
    private static final DefaultRedisScript<Long> RELEASE_ATTEMPT = new DefaultRedisScript<>(
            "local c = tonumber(redis.call('GET', KEYS[1])) "
                    + "if c and c > 0 then return redis.call('DECR', KEYS[1]) end "
                    + "return 0",
            Long.class);

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
        checkReservingAttempt(verifyKey, attemptKey, inputCode, codeTtlMinutes, maxAttempts);

        // 인증 성공 — 인증코드 및 오류 횟수 삭제
        redisTemplate.delete(verifyKey);
        redisTemplate.delete(attemptKey);
    }

    /**
     * 인증코드를 검증하되 <b>성공해도 코드를 소비(삭제)하지 않는다.</b>
     * 비밀번호 재설정처럼 "확인(pre-check) → 이후 같은 6자리 코드로 최종 처리" 흐름의
     * pre-check 단계에서 사용한다. 실패 시 오류 횟수 증가·최대치 초과 무효화는 동일하게 동작한다.
     * <p>
     * 정답이면 예약한 시도 1회를 되돌린다(AUTH-G10) - 정답 확인과 최종 reset 재검증, 그리고
     * SAME_AS_CURRENT_PASSWORD 같은 1차 실패 뒤의 재시도가 오답 한도를 깎으면 정상 사용자가 막힌다.
     *
     * @see #verify(String, String, String, long, int)
     */
    public void verifyWithoutConsume(String verifyKey, String attemptKey, String inputCode,
                                     long codeTtlMinutes, int maxAttempts) {
        checkReservingAttempt(verifyKey, attemptKey, inputCode, codeTtlMinutes, maxAttempts);
        // 성공 — 코드 유지(최종 reset 단계에서 같은 코드로 재검증·소비), 예약분만 환불
        releaseAttempt(attemptKey);
    }

    /**
     * 시도 횟수를 비교 <b>전에</b> 원자적으로 예약(INCR)한 뒤 비교한다 (AUTH-G10).
     * <p>
     * 예전 순서(GET → 비교 → 오답이면 INCR)는 코드가 지워지기 전에 GET한 병렬 요청이 모두 비교돼
     * 한도(5회)를 넘겨 추측할 수 있었다. 이제 예약 번호가 한도를 넘은 요청은 비교하지 않고 거절한다.
     * <ul>
     *   <li>코드가 없으면(만료·무효화) 예약하지 않고 EXPIRED_SMS_CODE - 만료 뒤 호출로 카운터가 늘지 않게.</li>
     *   <li>한도 도달·초과 시 인증코드만 지우고 <b>오류 횟수 키는 남긴다</b> - 지우면 이미 코드를 읽은 병렬
     *       요청이 새 카운터(1)로 다시 비교된다. 남은 키는 TTL 또는 재발송(sendCode)이 정리한다.</li>
     *   <li>Redis 장애는 그대로 전파한다(fail-closed) - 시도 제한이 꺼진 채 비교를 허용하지 않는다.</li>
     * </ul>
     * 정답이면 예외 없이 반환한다(예약분 처리는 호출자 몫 - 소비형은 키 삭제, 비소비형은 환불).
     */
    private void checkReservingAttempt(String verifyKey, String attemptKey, String inputCode,
                                       long codeTtlMinutes, int maxAttempts) {
        String savedCode = redisTemplate.opsForValue().get(verifyKey);

        // 인증코드가 없으면 만료된 것
        if (savedCode == null) {
            throw new CustomException(ErrorCode.EXPIRED_SMS_CODE);
        }

        // 비교 전 시도 1회 예약 + 최초 증가 시 TTL(인증코드와 동일) 설정을 원자적으로 (L-2)
        long attempts = redisCounter.incrementWithTtl(attemptKey, codeTtlMinutes * 60);

        // 한도를 넘은 예약은 비교하지 않는다 (병렬 오답이 한도를 넘겨 비교되던 문제)
        if (attempts > maxAttempts) {
            redisTemplate.delete(verifyKey);
            throw new CustomException(ErrorCode.SMS_TOO_MANY_ATTEMPTS);
        }

        if (codesMatch(savedCode, inputCode)) {
            return;
        }

        // 오답 — 예약분이 그대로 오류 1회로 남는다. 최대 오류 횟수 도달 시 인증코드 즉시 무효화
        if (attempts >= maxAttempts) {
            redisTemplate.delete(verifyKey);
            throw new CustomException(ErrorCode.SMS_TOO_MANY_ATTEMPTS);
        }

        throw new CustomException(ErrorCode.INVALID_SMS_CODE);
    }

    // 정답 시 예약분 환불. 실패해도 이미 정답 확인은 끝났으므로 응답을 막지 않는다(오류 1회로 남을 뿐).
    private void releaseAttempt(String attemptKey) {
        try {
            redisTemplate.execute(RELEASE_ATTEMPT, List.of(attemptKey));
        } catch (DataAccessException e) {
            log.warn("[VERIFY-ATTEMPT-RELEASE-FAILED] 시도 횟수 환불 실패 cause={}", e.getClass().getSimpleName());
        }
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
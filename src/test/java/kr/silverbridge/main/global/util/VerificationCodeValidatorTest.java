package kr.silverbridge.main.global.util;

import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.script.RedisScript;
import org.mockito.InOrder;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerificationCodeValidatorTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private RedisCounter redisCounter;

    @InjectMocks
    private VerificationCodeValidator verificationCodeValidator;

    private static final String VERIFY_KEY  = "sms:verify:01012345678";
    private static final String ATTEMPT_KEY = "sms:attempt:01012345678";
    private static final long CODE_TTL      = 5L;
    private static final int MAX_ATTEMPTS   = 5;

    @BeforeEach
    void setUp() {
        // consume() 등 opsForValue를 쓰지 않는 테스트도 있어 lenient로 둔다.
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("저장된 코드가 없으면 EXPIRED_SMS_CODE 예외")
    void expiredWhenNoCodeStored() {
        when(valueOperations.get(VERIFY_KEY)).thenReturn(null);

        assertThatThrownBy(() -> verificationCodeValidator.verify(
                VERIFY_KEY, ATTEMPT_KEY, "123456", CODE_TTL, MAX_ATTEMPTS))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.EXPIRED_SMS_CODE);
    }

    @Test
    @DisplayName("코드가 일치하면 인증키·오류 카운터가 즉시 삭제된다")
    void successDeletesKeys() {
        when(valueOperations.get(VERIFY_KEY)).thenReturn("123456");

        verificationCodeValidator.verify(VERIFY_KEY, ATTEMPT_KEY, "123456", CODE_TTL, MAX_ATTEMPTS);

        verify(redisTemplate).delete(VERIFY_KEY);
        verify(redisTemplate).delete(ATTEMPT_KEY);
    }

    @Test
    @DisplayName("코드 불일치 시 INVALID_SMS_CODE + 오류 카운터를 incrementWithTtl로 원자적 증가")
    void mismatchIncrementsAttemptCounter() {
        when(valueOperations.get(VERIFY_KEY)).thenReturn("123456");
        // 인증코드 TTL(분) → 초로 환산하여 호출되는지 검증
        when(redisCounter.incrementWithTtl(ATTEMPT_KEY, CODE_TTL * 60)).thenReturn(2L);

        assertThatThrownBy(() -> verificationCodeValidator.verify(
                VERIFY_KEY, ATTEMPT_KEY, "999999", CODE_TTL, MAX_ATTEMPTS))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SMS_CODE);

        verify(redisCounter).incrementWithTtl(eq(ATTEMPT_KEY), eq(CODE_TTL * 60));
        // 아직 MAX 미도달 → 키 삭제 없음
        verify(redisTemplate, never()).delete(VERIFY_KEY);
    }

    @Test
    @DisplayName("verifyWithoutConsume는 코드가 일치해도 키를 삭제하지 않는다 (검증/소비 분리)")
    void verifyWithoutConsumeKeepsKeysOnSuccess() {
        when(valueOperations.get(VERIFY_KEY)).thenReturn("123456");

        verificationCodeValidator.verifyWithoutConsume(VERIFY_KEY, ATTEMPT_KEY, "123456", CODE_TTL, MAX_ATTEMPTS);

        verify(redisTemplate, never()).delete(VERIFY_KEY);
        verify(redisTemplate, never()).delete(ATTEMPT_KEY);
    }

    @Test
    @DisplayName("consume는 인증키·오류 카운터를 삭제한다 (최종 성공 후 1회용 소비)")
    void consumeDeletesBothKeys() {
        assertThatCode(() -> verificationCodeValidator.consume(VERIFY_KEY, ATTEMPT_KEY))
                .doesNotThrowAnyException();

        verify(redisTemplate).delete(VERIFY_KEY);
        verify(redisTemplate).delete(ATTEMPT_KEY);
    }

    @Test
    @DisplayName("오류 횟수가 MAX에 도달하면 SMS_TOO_MANY_ATTEMPTS + 인증키 즉시 무효화")
    void maxAttemptsInvalidatesCode() {
        when(valueOperations.get(VERIFY_KEY)).thenReturn("123456");
        when(redisCounter.incrementWithTtl(ATTEMPT_KEY, CODE_TTL * 60)).thenReturn((long) MAX_ATTEMPTS);

        assertThatThrownBy(() -> verificationCodeValidator.verify(
                VERIFY_KEY, ATTEMPT_KEY, "999999", CODE_TTL, MAX_ATTEMPTS))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SMS_TOO_MANY_ATTEMPTS);

        verify(redisTemplate, times(1)).delete(VERIFY_KEY);
        // 오류 횟수 키는 남긴다 - 지우면 이미 코드를 읽은 병렬 요청이 새 카운터(1)로 다시 비교된다 (AUTH-G10)
        verify(redisTemplate, never()).delete(ATTEMPT_KEY);
    }

    // ─── AUTH-G10: 시도 횟수 선예약 ────────────────────────────────────────

    @Test
    @DisplayName("AUTH-G10: 코드 조회 → 시도 예약(INCR) → 비교 순서. 오답이면 예약분이 오류로 남는다(환불 없음)")
    void reservesAttemptBeforeCompare() {
        when(valueOperations.get(VERIFY_KEY)).thenReturn("123456");
        when(redisCounter.incrementWithTtl(ATTEMPT_KEY, CODE_TTL * 60)).thenReturn(1L);

        assertThatThrownBy(() -> verificationCodeValidator.verifyWithoutConsume(
                VERIFY_KEY, ATTEMPT_KEY, "999999", CODE_TTL, MAX_ATTEMPTS))
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SMS_CODE);

        InOrder inOrder = inOrder(valueOperations, redisCounter);
        inOrder.verify(valueOperations).get(VERIFY_KEY);
        inOrder.verify(redisCounter).incrementWithTtl(ATTEMPT_KEY, CODE_TTL * 60);
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList());
    }

    @Test
    @DisplayName("AUTH-G10: 예약 번호가 한도를 넘으면 정답이어도 비교하지 않고 SMS_TOO_MANY_ATTEMPTS")
    void reservationOverLimitIsRejectedWithoutCompare() {
        when(valueOperations.get(VERIFY_KEY)).thenReturn("123456");
        when(redisCounter.incrementWithTtl(ATTEMPT_KEY, CODE_TTL * 60)).thenReturn((long) MAX_ATTEMPTS + 1);

        assertThatThrownBy(() -> verificationCodeValidator.verify(
                VERIFY_KEY, ATTEMPT_KEY, "123456", CODE_TTL, MAX_ATTEMPTS))
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SMS_TOO_MANY_ATTEMPTS);

        verify(redisTemplate).delete(VERIFY_KEY);
        verify(redisTemplate, never()).delete(ATTEMPT_KEY);
    }

    @Test
    @DisplayName("AUTH-G10: 만료된 코드는 시도를 예약하지 않는다")
    void expiredCodeDoesNotReserve() {
        when(valueOperations.get(VERIFY_KEY)).thenReturn(null);

        assertThatThrownBy(() -> verificationCodeValidator.verifyWithoutConsume(
                VERIFY_KEY, ATTEMPT_KEY, "123456", CODE_TTL, MAX_ATTEMPTS))
                .extracting("errorCode")
                .isEqualTo(ErrorCode.EXPIRED_SMS_CODE);

        verify(redisCounter, never()).incrementWithTtl(anyString(), anyLong());
    }

    @Test
    @DisplayName("AUTH-G10: 동시 오답 30건 - 원자 카운터 기준 한도(5)까지만 오답 판정, 나머지는 비교 없이 거절")
    void concurrentWrongGuessesAreCappedByReservation() {
        when(valueOperations.get(VERIFY_KEY)).thenReturn("123456"); // 30건 모두 삭제 전에 코드를 읽었다고 가정
        AtomicLong counter = new AtomicLong();
        when(redisCounter.incrementWithTtl(ATTEMPT_KEY, CODE_TTL * 60)).thenAnswer(inv -> counter.incrementAndGet());

        int invalid = 0;
        int tooMany = 0;
        for (int i = 0; i < 30; i++) {
            try {
                verificationCodeValidator.verify(VERIFY_KEY, ATTEMPT_KEY, "999999", CODE_TTL, MAX_ATTEMPTS);
            } catch (CustomException e) {
                if (e.getErrorCode() == ErrorCode.INVALID_SMS_CODE) invalid++;
                if (e.getErrorCode() == ErrorCode.SMS_TOO_MANY_ATTEMPTS) tooMany++;
            }
        }

        // 오답으로 "비교"된 것은 1~5번째 예약뿐(4건 INVALID + 5번째에서 무효화), 6번째부터는 비교 전 거절
        assertThat(invalid).isEqualTo(MAX_ATTEMPTS - 1);
        assertThat(tooMany).isEqualTo(30 - (MAX_ATTEMPTS - 1));
    }

    @Test
    @DisplayName("AUTH-G10: 6번째 예약은 정답 코드라도 통과하지 못한다(한도 초과 추측 차단)")
    void sixthReservationCannotPassEvenWithCorrectCode() {
        when(valueOperations.get(VERIFY_KEY)).thenReturn("123456");
        when(redisCounter.incrementWithTtl(ATTEMPT_KEY, CODE_TTL * 60))
                .thenReturn(1L, 2L, 3L, 4L, 5L, 6L);

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertThatThrownBy(() -> verificationCodeValidator.verify(
                    VERIFY_KEY, ATTEMPT_KEY, "000000", CODE_TTL, MAX_ATTEMPTS))
                    .isInstanceOf(CustomException.class);
        }
        assertThatThrownBy(() -> verificationCodeValidator.verify(
                VERIFY_KEY, ATTEMPT_KEY, "123456", CODE_TTL, MAX_ATTEMPTS))
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SMS_TOO_MANY_ATTEMPTS);
    }

    @Test
    @DisplayName("AUTH-G10: verifyWithoutConsume 정답 → 예약분 환불 스크립트 호출, 키 삭제 없음")
    void verifyWithoutConsumeReleasesReservationOnSuccess() {
        when(valueOperations.get(VERIFY_KEY)).thenReturn("123456");
        when(redisCounter.incrementWithTtl(ATTEMPT_KEY, CODE_TTL * 60)).thenReturn(1L);

        verificationCodeValidator.verifyWithoutConsume(VERIFY_KEY, ATTEMPT_KEY, "123456", CODE_TTL, MAX_ATTEMPTS);

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(ATTEMPT_KEY)));
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("AUTH-G10: 정답 재시도(1차 실패 뒤 같은 코드)는 한도를 깎지 않아 몇 번이고 통과한다")
    void correctRetriesDoNotExhaustAttempts() {
        when(valueOperations.get(VERIFY_KEY)).thenReturn("123456");
        AtomicLong counter = new AtomicLong();
        when(redisCounter.incrementWithTtl(ATTEMPT_KEY, CODE_TTL * 60)).thenAnswer(inv -> counter.incrementAndGet());
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(ATTEMPT_KEY))))
                .thenAnswer(inv -> counter.decrementAndGet());

        for (int i = 0; i < MAX_ATTEMPTS * 2; i++) {
            verificationCodeValidator.verifyWithoutConsume(VERIFY_KEY, ATTEMPT_KEY, "123456", CODE_TTL, MAX_ATTEMPTS);
        }

        assertThat(counter.get()).isZero();
    }

    @Test
    @DisplayName("AUTH-G10: 환불이 Redis 오류로 실패해도 정답 확인은 성공으로 끝난다")
    void releaseFailureIsSwallowed() {
        when(valueOperations.get(VERIFY_KEY)).thenReturn("123456");
        when(redisCounter.incrementWithTtl(ATTEMPT_KEY, CODE_TTL * 60)).thenReturn(1L);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(ATTEMPT_KEY))))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> verificationCodeValidator.verifyWithoutConsume(
                VERIFY_KEY, ATTEMPT_KEY, "123456", CODE_TTL, MAX_ATTEMPTS))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AUTH-G10: 시도 예약이 Redis 오류면 비교하지 않고 그대로 실패한다(fail-closed)")
    void reservationFailureIsFailClosed() {
        when(valueOperations.get(VERIFY_KEY)).thenReturn("123456");
        when(redisCounter.incrementWithTtl(ATTEMPT_KEY, CODE_TTL * 60))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> verificationCodeValidator.verify(
                VERIFY_KEY, ATTEMPT_KEY, "123456", CODE_TTL, MAX_ATTEMPTS))
                .isInstanceOf(RedisConnectionFailureException.class);
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("소비형 verify 정답 → 키 삭제(소비)만 하고 환불 스크립트는 부르지 않는다")
    void verifySuccessDoesNotRelease() {
        when(valueOperations.get(VERIFY_KEY)).thenReturn("123456");
        when(redisCounter.incrementWithTtl(ATTEMPT_KEY, CODE_TTL * 60)).thenReturn(1L);

        verificationCodeValidator.verify(VERIFY_KEY, ATTEMPT_KEY, "123456", CODE_TTL, MAX_ATTEMPTS);

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList());
        verify(redisTemplate).delete(VERIFY_KEY);
        verify(redisTemplate).delete(ATTEMPT_KEY);
    }
}

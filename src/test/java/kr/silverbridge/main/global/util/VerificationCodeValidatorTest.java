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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
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
        verify(redisTemplate, times(1)).delete(ATTEMPT_KEY);
    }
}

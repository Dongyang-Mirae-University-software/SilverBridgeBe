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

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @InjectMocks
    private VerificationCodeValidator verificationCodeValidator;

    private static final String VERIFY_KEY  = "sms:verify:01012345678";
    private static final String ATTEMPT_KEY = "sms:attempt:01012345678";
    private static final long CODE_TTL      = 5L;
    private static final int MAX_ATTEMPTS   = 5;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
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
    @DisplayName("코드가 일치하지 않으면 INVALID_SMS_CODE 예외 + 오류 카운터 증가")
    void mismatchIncrementsAttemptCounter() {
        when(valueOperations.get(VERIFY_KEY)).thenReturn("123456");
        when(valueOperations.increment(ATTEMPT_KEY)).thenReturn(2L);

        assertThatThrownBy(() -> verificationCodeValidator.verify(
                VERIFY_KEY, ATTEMPT_KEY, "999999", CODE_TTL, MAX_ATTEMPTS))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SMS_CODE);

        verify(valueOperations).increment(ATTEMPT_KEY);
        verify(redisTemplate).expire(eq(ATTEMPT_KEY), anyLong(), eq(TimeUnit.MINUTES));
        // 아직 MAX 미도달 → 키 삭제 없음
        verify(redisTemplate, never()).delete(VERIFY_KEY);
    }

    @Test
    @DisplayName("오류 횟수가 MAX에 도달하면 SMS_TOO_MANY_ATTEMPTS + 인증키 즉시 무효화")
    void maxAttemptsInvalidatesCode() {
        when(valueOperations.get(VERIFY_KEY)).thenReturn("123456");
        when(valueOperations.increment(ATTEMPT_KEY)).thenReturn((long) MAX_ATTEMPTS);

        assertThatThrownBy(() -> verificationCodeValidator.verify(
                VERIFY_KEY, ATTEMPT_KEY, "999999", CODE_TTL, MAX_ATTEMPTS))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SMS_TOO_MANY_ATTEMPTS);

        verify(redisTemplate, times(1)).delete(VERIFY_KEY);
        verify(redisTemplate, times(1)).delete(ATTEMPT_KEY);
    }
}

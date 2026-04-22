package kr.silverbridge.main.global.security;

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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("첫 요청(count=1)은 통과하고 TTL이 설정된다")
    void firstRequestSetsTtl() {
        String key = "rate:email-check:127.0.0.1";
        when(valueOperations.increment(key)).thenReturn(1L);

        rateLimitService.check("email-check", "127.0.0.1");

        verify(redisTemplate, times(1)).expire(eq(key), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("창 내 10회 이하 요청은 모두 통과하며 TTL은 최초 한 번만 설정된다")
    void within10RequestsDoesNotThrow() {
        String key = "rate:email-check:127.0.0.1";
        when(valueOperations.increment(key)).thenReturn(5L);

        assertThatCode(() -> rateLimitService.check("email-check", "127.0.0.1"))
                .doesNotThrowAnyException();

        // count != 1 → TTL 재설정 없음
        verify(redisTemplate, never()).expire(anyString(), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("11회 초과 시 TOO_MANY_REQUESTS 예외")
    void exceedingLimitThrows() {
        String key = "rate:email-check:127.0.0.1";
        when(valueOperations.increment(key)).thenReturn(11L);

        assertThatThrownBy(() -> rateLimitService.check("email-check", "127.0.0.1"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("endpoint와 identifier가 조합되어 Redis 키가 만들어진다")
    void keyFormatUsesEndpointAndIdentifier() {
        when(valueOperations.increment("rate:signup-sms:user123")).thenReturn(1L);

        rateLimitService.check("signup-sms", "user123");

        verify(valueOperations).increment("rate:signup-sms:user123");
    }
}

package kr.silverbridge.main.global.security;

import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.util.RedisCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock
    private RedisCounter redisCounter;

    @InjectMocks
    private RateLimitService rateLimitService;

    @Test
    @DisplayName("창 내 10회 이하 요청은 통과한다 (incrementWithTtl로 원자적 처리)")
    void within10RequestsDoesNotThrow() {
        when(redisCounter.incrementWithTtl("rate:email-check:127.0.0.1", 60L)).thenReturn(5L);

        assertThatCode(() -> rateLimitService.check("email-check", "127.0.0.1"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("정확히 10회까지는 통과한다 (경계)")
    void exactlyMaxRequestsPasses() {
        when(redisCounter.incrementWithTtl("rate:email-check:127.0.0.1", 60L)).thenReturn(10L);

        assertThatCode(() -> rateLimitService.check("email-check", "127.0.0.1"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("11회 초과 시 TOO_MANY_REQUESTS 예외")
    void exceedingLimitThrows() {
        when(redisCounter.incrementWithTtl("rate:email-check:127.0.0.1", 60L)).thenReturn(11L);

        assertThatThrownBy(() -> rateLimitService.check("email-check", "127.0.0.1"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("endpoint와 identifier가 조합되어 Redis 키가 만들어지고 60초 TTL로 호출된다")
    void keyFormatUsesEndpointAndIdentifier() {
        when(redisCounter.incrementWithTtl("rate:signup-sms:user123", 60L)).thenReturn(1L);

        rateLimitService.check("signup-sms", "user123");

        verify(redisCounter).incrementWithTtl(eq("rate:signup-sms:user123"), eq(60L));
    }
}

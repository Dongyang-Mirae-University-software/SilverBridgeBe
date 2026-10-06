package kr.silverbridge.main.global.security;

import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.exception.TooManyRequestsException;
import kr.silverbridge.main.global.util.RedisCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

    // ─── 이중 윈도우(1분/1시간) 오버로드 — 비밀번호 재설정 (2026-05-23) ──────────────

    @Test
    @DisplayName("분·시간 한도 이내(경계 10/30)면 통과한다")
    void dualWindow_withinBothLimits_passes() {
        when(redisCounter.incrementWithTtl("rate:pw-reset-email:1m:1.2.3.4", 60L)).thenReturn(10L);
        when(redisCounter.incrementWithTtl("rate:pw-reset-email:1h:1.2.3.4", 3600L)).thenReturn(30L);

        assertThatCode(() -> rateLimitService.check("pw-reset-email", "1.2.3.4", 10, 30))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("분 한도 초과 시 TOO_MANY_REQUESTS")
    void dualWindow_minuteExceeded_throws() {
        when(redisCounter.incrementWithTtl("rate:pw-reset-email:1m:1.2.3.4", 60L)).thenReturn(11L);
        when(redisCounter.incrementWithTtl("rate:pw-reset-email:1h:1.2.3.4", 3600L)).thenReturn(5L);

        assertThatThrownBy(() -> rateLimitService.check("pw-reset-email", "1.2.3.4", 10, 30))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("시간 한도 초과 시 TOO_MANY_REQUESTS (분은 여유 있어도 차단 — 분산 저빈도 스윕 방어)")
    void dualWindow_hourExceeded_throws() {
        when(redisCounter.incrementWithTtl("rate:pw-reset-email:1m:1.2.3.4", 60L)).thenReturn(3L);
        when(redisCounter.incrementWithTtl("rate:pw-reset-email:1h:1.2.3.4", 3600L)).thenReturn(31L);

        assertThatThrownBy(() -> rateLimitService.check("pw-reset-email", "1.2.3.4", 10, 30))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("이중 윈도우는 1m/1h 접미 키와 각각 60초·3600초 TTL로 두 카운터를 모두 증가시킨다")
    void dualWindow_keyFormatAndTtls() {
        when(redisCounter.incrementWithTtl("rate:pw-reset-sms:1m:9.9.9.9", 60L)).thenReturn(1L);
        when(redisCounter.incrementWithTtl("rate:pw-reset-sms:1h:9.9.9.9", 3600L)).thenReturn(1L);

        rateLimitService.check("pw-reset-sms", "9.9.9.9", 10, 30);

        verify(redisCounter).incrementWithTtl(eq("rate:pw-reset-sms:1m:9.9.9.9"), eq(60L));
        verify(redisCounter).incrementWithTtl(eq("rate:pw-reset-sms:1h:9.9.9.9"), eq(3600L));
    }

    // ─── 남은 대기 시간(retryAfter) · Redis 장애 fail-open (2026-10-02) ──────────────

    @Test
    @DisplayName("초과 시 TooManyRequestsException에 키의 남은 TTL을 싣는다 (CustomException 하위 - 기존 계약 유지)")
    void exceeding_carriesRemainingTtl() {
        when(redisCounter.incrementWithTtl("rate:signin:1.2.3.4", 60L)).thenReturn(11L);
        when(redisCounter.remainingTtlSeconds("rate:signin:1.2.3.4")).thenReturn(42L);

        assertThatThrownBy(() -> rateLimitService.check("signin", "1.2.3.4"))
                .isInstanceOf(TooManyRequestsException.class)
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOO_MANY_REQUESTS)
                .hasFieldOrPropertyWithValue("retryAfterSeconds", 42L);
    }

    @Test
    @DisplayName("TTL을 알 수 없으면(-1) 윈도우 길이로 안내한다")
    void exceeding_unknownTtl_fallsBackToWindow() {
        when(redisCounter.incrementWithTtl("rate:signin:1.2.3.4", 60L)).thenReturn(11L);
        when(redisCounter.remainingTtlSeconds("rate:signin:1.2.3.4")).thenReturn(-1L);

        assertThatThrownBy(() -> rateLimitService.check("signin", "1.2.3.4"))
                .hasFieldOrPropertyWithValue("retryAfterSeconds", 60L);
    }

    @Test
    @DisplayName("TTL 0초(만료 직전)는 1초로 안내한다")
    void exceeding_zeroTtl_isAtLeastOneSecond() {
        when(redisCounter.incrementWithTtl("rate:signin:1.2.3.4", 60L)).thenReturn(11L);
        when(redisCounter.remainingTtlSeconds("rate:signin:1.2.3.4")).thenReturn(0L);

        assertThatThrownBy(() -> rateLimitService.check("signin", "1.2.3.4"))
                .hasFieldOrPropertyWithValue("retryAfterSeconds", 1L);
    }

    @Test
    @DisplayName("TTL 조회가 실패해도 429는 그대로, 대기 시간은 윈도우 길이")
    void exceeding_ttlLookupFails_stillThrows429() {
        when(redisCounter.incrementWithTtl("rate:signin:1.2.3.4", 60L)).thenReturn(11L);
        when(redisCounter.remainingTtlSeconds("rate:signin:1.2.3.4"))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> rateLimitService.check("signin", "1.2.3.4"))
                .isInstanceOf(TooManyRequestsException.class)
                .hasFieldOrPropertyWithValue("retryAfterSeconds", 60L);
    }

    @Test
    @DisplayName("이중 윈도우 - 분만 초과면 분 키 TTL, 시간 초과면 시간 키 TTL로 안내")
    void dualWindow_retryAfterFromExceededWindow() {
        when(redisCounter.incrementWithTtl("rate:pw-reset-email:1m:1.2.3.4", 60L)).thenReturn(11L);
        when(redisCounter.incrementWithTtl("rate:pw-reset-email:1h:1.2.3.4", 3600L)).thenReturn(5L);
        when(redisCounter.remainingTtlSeconds("rate:pw-reset-email:1m:1.2.3.4")).thenReturn(20L);

        assertThatThrownBy(() -> rateLimitService.check("pw-reset-email", "1.2.3.4", 10, 30))
                .hasFieldOrPropertyWithValue("retryAfterSeconds", 20L);

        when(redisCounter.incrementWithTtl("rate:pw-reset-email:1m:1.2.3.4", 60L)).thenReturn(11L);
        when(redisCounter.incrementWithTtl("rate:pw-reset-email:1h:1.2.3.4", 3600L)).thenReturn(31L);
        when(redisCounter.remainingTtlSeconds("rate:pw-reset-email:1h:1.2.3.4")).thenReturn(1800L);

        assertThatThrownBy(() -> rateLimitService.check("pw-reset-email", "1.2.3.4", 10, 30))
                .hasFieldOrPropertyWithValue("retryAfterSeconds", 1800L);
    }

    @Test
    @DisplayName("Redis 장애 시 fail-open - 예외 없이 통과한다(속도제한은 보조 방어)")
    void redisDown_failOpen() {
        when(redisCounter.incrementWithTtl("rate:signin:1.2.3.4", 60L))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> rateLimitService.check("signin", "1.2.3.4"))
                .doesNotThrowAnyException();
        verify(redisCounter, never()).remainingTtlSeconds(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Redis 장애 시 이중 윈도우도 fail-open")
    void redisDown_dualWindow_failOpen() {
        when(redisCounter.incrementWithTtl("rate:pw-reset-sms:1m:1.2.3.4", 60L))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> rateLimitService.check("pw-reset-sms", "1.2.3.4", 10, 30))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Redis 외 예외(프로그래밍 오류)는 삼키지 않는다")
    void nonRedisError_propagates() {
        when(redisCounter.incrementWithTtl("rate:signin:1.2.3.4", 60L))
                .thenThrow(new IllegalStateException("bug"));

        assertThatThrownBy(() -> rateLimitService.check("signin", "1.2.3.4"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ─── 호출자가 상한을 정하는 1분 윈도우 - 로그인 사용자 기준 제한 (2026-10-06) ──────────

    @Test
    @DisplayName("사용자 기준: 상한(600) 경계까지는 통과하고 601번째는 429 예외(대기 시간 포함)")
    void userLimit_boundary() {
        when(redisCounter.incrementWithTtl("rate:user-api:7", 60L)).thenReturn(600L, 601L);
        when(redisCounter.remainingTtlSeconds("rate:user-api:7")).thenReturn(33L);

        assertThatCode(() -> rateLimitService.check("user-api", "7", 600)).doesNotThrowAnyException();
        assertThatThrownBy(() -> rateLimitService.check("user-api", "7", 600))
                .isInstanceOf(TooManyRequestsException.class)
                .extracting("retryAfterSeconds")
                .isEqualTo(33L);
    }

    @Test
    @DisplayName("사용자 기준: Redis 장애 시 fail-open")
    void userLimit_redisDown_failOpen() {
        when(redisCounter.incrementWithTtl("rate:user-api:7", 60L))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> rateLimitService.check("user-api", "7", 600)).doesNotThrowAnyException();
    }
}

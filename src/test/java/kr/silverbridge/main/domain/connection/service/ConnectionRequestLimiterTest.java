package kr.silverbridge.main.domain.connection.service;

import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.exception.TooManyRequestsException;
import kr.silverbridge.main.global.util.RedisCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 같은 (보호자, 피보호자) 쌍의 연결 요청 반복 제한 (CONN-G04 / XCUT-G29).
 * Redis 값은 메모리 맵으로 흉내 내 "5건까지 자유, 6번째부터 쿨다운"을 그대로 따라간다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConnectionRequestLimiterTest {

    private static final String GUARDIAN_ID = "GD0001";
    private static final String WARD_ID = "WD0001";
    private static final String KEY = "connection:request:count:GD0001:WD0001";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private RedisCounter redisCounter;

    private final Map<String, Long> store = new HashMap<>();
    private ConnectionRequestLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new ConnectionRequestLimiter(redisTemplate, redisCounter);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(inv -> {
            Long v = store.get(inv.<String>getArgument(0));
            return v == null ? null : String.valueOf(v);
        });
        when(redisCounter.incrementWithTtl(anyString(), anyLong()))
                .thenAnswer(inv -> store.merge(inv.<String>getArgument(0), 1L, Long::sum));
        when(redisTemplate.delete(anyString())).thenAnswer(inv -> store.remove(inv.<String>getArgument(0)) != null);
        when(redisCounter.remainingTtlSeconds(KEY)).thenReturn(7200L);
    }

    /** 서비스가 하는 것과 같은 순서: 검사 → (생성) → 증가 */
    private void request() {
        limiter.checkAllowed(GUARDIAN_ID, WARD_ID);
        limiter.recordRequest(GUARDIAN_ID, WARD_ID);
    }

    @Test
    @DisplayName("5번째 요청까지는 자유롭게 통과한다")
    void 다섯번까지_통과() {
        for (int i = 0; i < 5; i++) {
            assertThatCode(this::request).doesNotThrowAnyException();
        }
        assertThat(store.get(KEY)).isEqualTo(5L);
    }

    @Test
    @DisplayName("6번째 요청부터 429 CONNECTION_REQUEST_COOLDOWN + 남은 TTL을 retryAfter로 - 이후에도 계속 거절")
    void 여섯번째부터_거절() {
        for (int i = 0; i < 5; i++) {
            request();
        }

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> limiter.checkAllowed(GUARDIAN_ID, WARD_ID))
                    .isInstanceOfSatisfying(TooManyRequestsException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_REQUEST_COOLDOWN);
                        assertThat(e.getRetryAfterSeconds()).isEqualTo(7200L);
                    });
        }
        // 거절된 요청은 세지 않는다(서비스가 증가까지 가지 않음)
        assertThat(store.get(KEY)).isEqualTo(5L);
    }

    @Test
    @DisplayName("첫 증가에 24시간 TTL을 건다")
    void 증가시_24시간_TTL() {
        limiter.recordRequest(GUARDIAN_ID, WARD_ID);

        verify(redisCounter).incrementWithTtl(KEY, 24 * 60 * 60L);
    }

    @Test
    @DisplayName("남은 TTL을 못 읽으면 24시간으로 안내한다")
    void TTL_없으면_윈도우로_안내() {
        store.put(KEY, 5L);
        when(redisCounter.remainingTtlSeconds(KEY)).thenReturn(-1L);

        assertThatThrownBy(() -> limiter.checkAllowed(GUARDIAN_ID, WARD_ID))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(24 * 60 * 60L));
    }

    @Test
    @DisplayName("수락 후 reset하면 다시 요청할 수 있다")
    void 리셋후_다시_허용() {
        for (int i = 0; i < 5; i++) {
            request();
        }

        limiter.reset(GUARDIAN_ID, WARD_ID);

        assertThatCode(() -> limiter.checkAllowed(GUARDIAN_ID, WARD_ID)).doesNotThrowAnyException();
        assertThat(store).doesNotContainKey(KEY);
    }

    @Test
    @DisplayName("다른 피보호자에게 보낸 요청은 따로 센다")
    void 쌍별로_따로() {
        for (int i = 0; i < 5; i++) {
            request();
        }

        assertThatCode(() -> limiter.checkAllowed(GUARDIAN_ID, "WD0002")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Redis 장애 시 fail-open - 검사·증가·삭제 모두 예외 없이 통과(정상 연결 요청을 막지 않는다)")
    void 레디스장애_fail_open() {
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        when(redisCounter.incrementWithTtl(anyString(), anyLong())).thenThrow(new RedisConnectionFailureException("down"));
        when(redisTemplate.delete(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> limiter.checkAllowed(GUARDIAN_ID, WARD_ID)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.recordRequest(GUARDIAN_ID, WARD_ID)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.reset(GUARDIAN_ID, WARD_ID)).doesNotThrowAnyException();
    }
}

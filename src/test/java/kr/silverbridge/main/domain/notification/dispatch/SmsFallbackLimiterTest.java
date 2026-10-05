package kr.silverbridge.main.domain.notification.dispatch;

import kr.silverbridge.main.domain.notification.config.SmsFallbackProperties;
import kr.silverbridge.main.global.util.RedisCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** SmsFallbackLimiter·SmsFallbackProperties 단위 테스트 - 시간당 30건 경계, 끔, fail-open, 키 구조, 환불, 설정 방어. */
@ExtendWith(MockitoExtension.class)
class SmsFallbackLimiterTest {

    private static final String KEY = "notify:sms-fallback:WARD_SOS:GD0001";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedisCounter redisCounter;

    private SmsFallbackLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new SmsFallbackLimiter(redisTemplate, redisCounter, new SmsFallbackProperties());
    }

    @Test
    @DisplayName("기본 상한은 시간당 30건")
    void 기본_30건() {
        assertThat(new SmsFallbackProperties().effectiveMaxPerHour()).isEqualTo(30);
    }

    @Test
    @DisplayName("30번째는 허용, 31번째부터 생략")
    void 경계_30허용_31생략() {
        when(redisCounter.incrementWithTtl(KEY, 3600)).thenReturn(30L, 31L);

        assertThat(limiter.tryAcquire(NotificationType.WARD_SOS, "GD0001")).isTrue();
        assertThat(limiter.tryAcquire(NotificationType.WARD_SOS, "GD0001")).isFalse();
    }

    @Test
    @DisplayName("증가와 TTL(1시간)은 원자적 유틸 한 번으로 처리한다 - 따로 부르면 TTL 없는 키가 남을 수 있다")
    void 원자적_증가_TTL_1시간() {
        when(redisCounter.incrementWithTtl(KEY, 3600)).thenReturn(1L);

        assertThat(limiter.tryAcquire(NotificationType.WARD_SOS, "GD0001")).isTrue();

        verify(redisCounter).incrementWithTtl(KEY, 3600);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("키는 알림 종류와 수신자별 - 다른 수신자의 한도를 쓰지 않는다")
    void 수신자별_키() {
        when(redisCounter.incrementWithTtl("notify:sms-fallback:WARD_SOS:GD0002", 3600)).thenReturn(31L);

        assertThat(limiter.tryAcquire(NotificationType.WARD_SOS, "GD0002")).isFalse();
        verify(redisCounter, never()).incrementWithTtl(eq(KEY), any(Long.class));
    }

    @Test
    @DisplayName("상한 0 = 끔 - Redis를 건드리지 않고 항상 허용하며 환불도 하지 않는다")
    void 상한_0은_끔() {
        SmsFallbackProperties props = new SmsFallbackProperties();
        props.setMaxPerHour(0);
        SmsFallbackLimiter off = new SmsFallbackLimiter(redisTemplate, redisCounter, props);

        assertThat(off.tryAcquire(NotificationType.WARD_SOS, "GD0001")).isTrue();
        off.release(NotificationType.WARD_SOS, "GD0001");

        verifyNoInteractions(redisTemplate, redisCounter);
    }

    @Test
    @DisplayName("Redis 장애 → 긴급 우선 fail-open으로 허용")
    void Redis장애_failOpen() {
        when(redisCounter.incrementWithTtl(anyString(), any(Long.class))).thenThrow(new RuntimeException("redis down"));

        assertThat(limiter.tryAcquire(NotificationType.WARD_SOS, "GD0001")).isTrue();
    }

    @Test
    @DisplayName("증가값을 읽지 못하면(0) 막지 않는다")
    void 증가값_0_허용() {
        when(redisCounter.incrementWithTtl(anyString(), any(Long.class))).thenReturn(0L);

        assertThat(limiter.tryAcquire(NotificationType.WARD_SOS, "GD0001")).isTrue();
    }

    @Test
    @DisplayName("release → 같은 키에 환불 스크립트를 실행한다(키가 있고 0보다 클 때만 감소 - 만료된 창에 키를 만들지 않는다)")
    void release_환불() {
        limiter.release(NotificationType.WARD_SOS, "GD0001");

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(KEY)));
    }

    @Test
    @DisplayName("release 중 Redis 장애 → 예외를 삼킨다")
    void release_장애_삼킴() {
        when(redisTemplate.execute(any(RedisScript.class), any(List.class))).thenThrow(new QueryTimeoutException("down"));

        assertThatNoException().isThrownBy(() -> limiter.release(NotificationType.WARD_SOS, "GD0001"));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE})
    @DisplayName("음수 설정은 기본 30건으로 대체")
    void 음수는_기본값(int value) {
        SmsFallbackProperties props = new SmsFallbackProperties();
        props.setMaxPerHour(value);

        assertThat(props.effectiveMaxPerHour()).isEqualTo(30);
    }

    @Test
    @DisplayName("설정 키가 바인딩된다 / 비숫자는 기동 실패(fail-fast)")
    void 바인딩() {
        Binder ok = new Binder(new MapConfigurationPropertySource(Map.of("notification.sms-fallback.max-per-hour", "12")));
        assertThat(ok.bind("notification.sms-fallback", SmsFallbackProperties.class).get().effectiveMaxPerHour()).isEqualTo(12);

        Binder bad = new Binder(new MapConfigurationPropertySource(Map.of("notification.sms-fallback.max-per-hour", "")));
        assertThatThrownBy(() -> bad.bind("notification.sms-fallback", SmsFallbackProperties.class))
                .isInstanceOf(BindException.class);
    }
}

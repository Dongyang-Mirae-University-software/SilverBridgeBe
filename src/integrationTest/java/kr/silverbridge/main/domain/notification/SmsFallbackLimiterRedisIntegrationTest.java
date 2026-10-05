package kr.silverbridge.main.domain.notification;

import kr.silverbridge.main.domain.notification.config.SmsFallbackProperties;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.notification.dispatch.SmsFallbackLimiter;
import kr.silverbridge.main.global.util.RedisCounter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문자 폴백 상한의 Lua 스크립트(증가+TTL, 환불)를 <b>실제 Redis</b>에서 검증한다.
 *
 * <p>단위 테스트는 Redis 호출을 목으로 대체해 스크립트가 실제로 도는지, 만료된 창에서 키가 되살아나지 않는지,
 * 동시 호출에서 상한이 정확한지를 보지 못한다. 운영과 같은 {@code redis:7.2}를 쓴다. Spring 컨텍스트 없이
 * Lettuce 연결만 직접 만든다(필수 설정 검증기·Firebase 등이 같이 뜨지 않게).</p>
 */
class SmsFallbackLimiterRedisIntegrationTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2").withExposedPorts(6379);
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;

    private SmsFallbackLimiter limiter;

    @BeforeAll
    static void start() {
        REDIS.start();
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void stop() {
        factory.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        template.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        limiter = new SmsFallbackLimiter(template, new RedisCounter(template), new SmsFallbackProperties());
    }

    private static String key(String recipient) {
        return "notify:sms-fallback:WARD_SOS:" + recipient;
    }

    @Test
    @DisplayName("첫 호출에 1시간 TTL이 걸리고, 30번째까지 허용·31번째부터 생략하며 차단 뒤에도 TTL이 유지된다")
    void 경계와_TTL() {
        for (int i = 1; i <= 30; i++) {
            assertThat(limiter.tryAcquire(NotificationType.WARD_SOS, "GD1")).as("%d번째", i).isTrue();
        }
        Long ttl = template.getExpire(key("GD1"));
        assertThat(ttl).isBetween(1L, 3600L);

        assertThat(limiter.tryAcquire(NotificationType.WARD_SOS, "GD1")).isFalse();
        assertThat(limiter.tryAcquire(NotificationType.WARD_SOS, "GD1")).isFalse();
        assertThat(template.getExpire(key("GD1"))).as("차단 뒤에도 TTL이 있어야 한다(영구 키 방지)").isBetween(1L, 3600L);
    }

    @Test
    @DisplayName("환불하면 한 자리가 돌아와 다시 허용된다")
    void 환불() {
        for (int i = 0; i < 30; i++) {
            limiter.tryAcquire(NotificationType.WARD_SOS, "GD2");
        }
        assertThat(limiter.tryAcquire(NotificationType.WARD_SOS, "GD2")).as("31번째(카운트 31)").isFalse();

        limiter.release(NotificationType.WARD_SOS, "GD2"); // 31 → 30
        limiter.release(NotificationType.WARD_SOS, "GD2"); // 30 → 29
        assertThat(limiter.tryAcquire(NotificationType.WARD_SOS, "GD2")).as("카운트 30").isTrue();
        assertThat(limiter.tryAcquire(NotificationType.WARD_SOS, "GD2")).as("카운트 31").isFalse();
    }

    @Test
    @DisplayName("창이 이미 만료되어 키가 없으면 환불해도 키를 만들지 않는다 - TTL 없는 영구 키 방지")
    void 만료된_창의_환불은_키를_만들지_않는다() {
        limiter.release(NotificationType.WARD_SOS, "GD3");

        assertThat(template.hasKey(key("GD3"))).isFalse();
    }

    @Test
    @DisplayName("0 이하로는 내려가지 않는다 - 연속 환불이 음수 카운터를 만들지 않는다")
    void 환불은_0_아래로_내려가지_않는다() {
        limiter.tryAcquire(NotificationType.WARD_SOS, "GD4"); // 1
        limiter.release(NotificationType.WARD_SOS, "GD4");    // 0
        limiter.release(NotificationType.WARD_SOS, "GD4");    // 그대로 0
        limiter.release(NotificationType.WARD_SOS, "GD4");

        assertThat(template.opsForValue().get(key("GD4"))).isEqualTo("0");
    }

    @Test
    @DisplayName("수신자별로 한도가 분리된다")
    void 수신자별_분리() {
        for (int i = 0; i < 31; i++) {
            limiter.tryAcquire(NotificationType.WARD_SOS, "GD5");
        }

        assertThat(limiter.tryAcquire(NotificationType.WARD_SOS, "GD5")).isFalse();
        assertThat(limiter.tryAcquire(NotificationType.WARD_SOS, "GD6")).isTrue();
    }

    @Test
    @DisplayName("동시에 100번 불러도 정확히 30번만 허용된다")
    void 동시_호출() throws Exception {
        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Boolean> task = () -> {
                ready.countDown();
                go.await();
                return limiter.tryAcquire(NotificationType.WARD_SOS, "GD7");
            };
            results.add(pool.submit(task));
        }
        ready.await();
        go.countDown();

        int allowed = 0;
        for (Future<Boolean> f : results) {
            if (f.get(30, TimeUnit.SECONDS)) {
                allowed++;
            }
        }
        pool.shutdown();

        assertThat(allowed).isEqualTo(30);
        assertThat(template.getExpire(key("GD7"))).isBetween(1L, 3600L);
    }
}

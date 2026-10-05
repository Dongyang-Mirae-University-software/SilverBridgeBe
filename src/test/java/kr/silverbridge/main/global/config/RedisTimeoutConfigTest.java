package kr.silverbridge.main.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 명령·연결 시간 제한 고정 (QA XCUT-G11).
 *
 * <p>SOS 경로(인증 필터·SOS 알림 쿨다운)는 Redis를 거친다. 시간 제한이 빠지면 Lettuce 기본(명령 60초)으로 돌아가
 * Redis가 응답하지 않을 때 요청 스레드·긴급 알림 스레드가 1분씩 묶인다.</p>
 */
class RedisTimeoutConfigTest {

    @Test
    @DisplayName("application.yaml 기본값 - 명령 2초·연결 2초")
    void 기본값() throws Exception {
        Binder binder = binder(Map.of());

        assertThat(binder.bind("spring.data.redis.timeout", Duration.class).get()).isEqualTo(Duration.ofSeconds(2));
        assertThat(binder.bind("spring.data.redis.connect-timeout", Duration.class).get())
                .isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("REDIS_TIMEOUT·REDIS_CONNECT_TIMEOUT으로 재정의할 수 있다")
    void 환경변수_재정의() throws Exception {
        Binder binder = binder(Map.of("REDIS_TIMEOUT", "500ms", "REDIS_CONNECT_TIMEOUT", "1s"));

        assertThat(binder.bind("spring.data.redis.timeout", Duration.class).get()).isEqualTo(Duration.ofMillis(500));
        assertThat(binder.bind("spring.data.redis.connect-timeout", Duration.class).get())
                .isEqualTo(Duration.ofSeconds(1));
    }

    private Binder binder(Map<String, Object> env) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-env", env));
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment);
    }
}

package kr.silverbridge.main.global.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Solapi 호출 시간 제한(QA P16) - SDK가 50초 고정 제한이라 호출자가 설정 시간 안에 풀려나는지 확인한다.
 */
@DisplayName("SolapiCallExecutor - Solapi 호출 시간 제한")
class SolapiCallExecutorTest {

    private final List<SolapiCallExecutor> created = new ArrayList<>();

    @AfterEach
    void tearDown() {
        created.forEach(SolapiCallExecutor::shutdown);
    }

    private SolapiCallExecutor executor(Duration timeout) {
        SolapiCallExecutor executor = new SolapiCallExecutor(timeout);
        created.add(executor);
        return executor;
    }

    @Test
    @DisplayName("제한 시간 안에 끝나면 결과를 그대로 돌려준다")
    void 정상_호출() {
        Optional<String> result = executor(Duration.ofSeconds(2)).call("SMS", () -> "ok");

        assertThat(result).contains("ok");
    }

    @Test
    @DisplayName("SDK가 제한 시간을 넘기면 호출자는 제한 시간 안에 빈 결과로 돌아오고 작업은 인터럽트된다")
    void 시간초과_호출자_해제() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);

        long start = System.nanoTime();
        Optional<String> result = executor(Duration.ofMillis(300)).call("SMS", () -> {
            try {
                Thread.sleep(10_000);   // SDK 50초 고정 제한을 흉내 낸 느린 호출
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
            return "late";
        });
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(result).isEmpty();
        assertThat(elapsedMillis).isLessThan(3_000);
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).as("cancel(true)로 인터럽트").isTrue();
    }

    @Test
    @DisplayName("작업이 던진 런타임 예외는 그대로 다시 던진다(기존 동작 보존)")
    void 런타임예외_전파() {
        IllegalStateException boom = new IllegalStateException("boom");

        assertThatThrownBy(() -> executor(Duration.ofSeconds(2)).call("SMS", () -> {
            throw boom;
        })).isSameAs(boom);
    }

    @Test
    @DisplayName("작업이 던진 checked 예외는 빈 결과(통신 오류)로 바뀐다")
    void checked예외_빈결과() {
        Optional<String> result = executor(Duration.ofSeconds(2)).call("SMS", () -> {
            throw new IOException("01012345678 연결 실패");
        });

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("풀과 큐가 모두 차면 기다리지 않고 빈 결과로 돌아온다")
    void 풀포화_즉시실패() throws Exception {
        SolapiCallExecutor executor = executor(Duration.ofSeconds(5));
        CountDownLatch release = new CountDownLatch(1);
        int capacity = SolapiCallExecutor.POOL_SIZE + SolapiCallExecutor.QUEUE_CAPACITY;
        List<Thread> callers = new ArrayList<>();
        CountDownLatch submitted = new CountDownLatch(capacity);
        for (int i = 0; i < capacity; i++) {
            Thread caller = new Thread(() -> {
                submitted.countDown();
                executor.call("SMS", () -> release.await(10, TimeUnit.SECONDS));
            });
            caller.start();
            callers.add(caller);
        }
        submitted.await(2, TimeUnit.SECONDS);
        Thread.sleep(300);   // 제출이 풀·큐를 채울 시간

        long start = System.nanoTime();
        Optional<Boolean> result = executor.call("SMS", () -> true);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        release.countDown();
        for (Thread caller : callers) {
            caller.join(5_000);
        }
        assertThat(result).isEmpty();
        assertThat(elapsedMillis).isLessThan(1_000);
    }

    @Test
    @DisplayName("제한 시간이 0 이하이면 기동 시 거부한다")
    void 잘못된_제한시간() {
        assertThatThrownBy(() -> new SolapiCallExecutor(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SolapiCallProperties bind(Map<String, Object> env) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-env", env));
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment).bind("solapi", SolapiCallProperties.class).get();
    }

    @Test
    @DisplayName("application.yaml 기본값은 10초")
    void 설정_기본값() throws Exception {
        assertThat(bind(Map.of()).getCallTimeoutSeconds()).isEqualTo(10);
    }

    @Test
    @DisplayName("SOLAPI_CALL_TIMEOUT_SECONDS로 재정의할 수 있다")
    void 설정_환경변수_재정의() throws Exception {
        assertThat(bind(Map.of("SOLAPI_CALL_TIMEOUT_SECONDS", "5")).getCallTimeoutSeconds()).isEqualTo(5);
    }
}

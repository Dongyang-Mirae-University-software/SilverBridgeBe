package kr.silverbridge.main.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 우아한 종료 시간 예산(2026-11-21 최종 점검 M-1): 종료 단계 대기({@code spring.lifecycle.timeout-per-shutdown-phase})
 * + 풀 종료 대기({@link AsyncConfig}의 최댓값)가 컨테이너 grace({@code stop_grace_period})보다 짧아야 한다.
 * 넘으면 진행 중 요청이 길 때 Docker가 SIGKILL을 보내 큐의 알림(SOS 포함)이 잘린다 - 기본 30초 + 20초가 40초를 넘던 것이
 * 이 점검의 발견이다. 컨텍스트를 띄우지 않고 설정 파일과 풀 설정을 직접 읽는다.
 */
class ShutdownGraceBudgetTest {

    private static final Path APPLICATION_YAML = Path.of("src/main/resources/application.yaml");
    private static final List<Path> COMPOSE_FILES = List.of(Path.of("docker-compose.dev.yml"), Path.of("docker-compose.prod.yml"));
    private static final Pattern PHASE_TIMEOUT = Pattern.compile("(?m)^\\s+timeout-per-shutdown-phase:\\s*(\\S+)");
    private static final Pattern STOP_GRACE = Pattern.compile("(?m)^\\s+stop_grace_period:\\s*(\\S+)");

    @Test
    @DisplayName("종료 단계 대기 + 풀 종료 대기 최댓값 < api 컨테이너 stop_grace_period (dev·prod)")
    void 종료_대기_합이_컨테이너_grace보다_짧다() throws Exception {
        Duration phase = phaseTimeout();
        Duration poolAwait = maxPoolAwait();
        Duration budget = phase.plus(poolAwait);

        assertThat(poolAwait).as("AsyncConfig 풀 종료 대기").isPositive();
        for (Path compose : COMPOSE_FILES) {
            assertThat(budget)
                    .as("%s: 단계 대기 %s + 풀 종료 대기 %s 는 stop_grace_period 보다 짧아야 한다", compose, phase, poolAwait)
                    .isLessThan(stopGrace(compose));
        }
    }

    private static Duration phaseTimeout() throws IOException {
        Matcher matcher = PHASE_TIMEOUT.matcher(Files.readString(APPLICATION_YAML));
        assertThat(matcher.find())
                .as("application.yaml 에 spring.lifecycle.timeout-per-shutdown-phase 가 있어야 한다(없으면 기본 30초)")
                .isTrue();
        return DurationStyle.detectAndParse(matcher.group(1));
    }

    /** {@link AsyncConfig}가 만드는 풀 전부의 종료 대기 중 가장 긴 값. */
    private static Duration maxPoolAwait() throws Exception {
        AsyncConfig config = new AsyncConfig();
        List<ThreadPoolTaskExecutor> executors = new ArrayList<>();
        try {
            for (Method method : AsyncConfig.class.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Bean.class) && method.getParameterCount() == 0
                        && method.invoke(config) instanceof ThreadPoolTaskExecutor executor) {
                    executors.add(executor);
                }
            }
            assertThat(executors).as("AsyncConfig 풀").isNotEmpty();
            long maxMillis = 0;
            for (ThreadPoolTaskExecutor executor : executors) {
                assertThat(ReflectionTestUtils.getField(executor, "waitForTasksToCompleteOnShutdown"))
                        .as("풀은 종료 때 큐를 비우고 끝낸다").isEqualTo(true);
                maxMillis = Math.max(maxMillis, (long) ReflectionTestUtils.getField(executor, "awaitTerminationMillis"));
            }
            return Duration.ofMillis(maxMillis);
        } finally {
            executors.forEach(ThreadPoolTaskExecutor::shutdown);
        }
    }

    private static Duration stopGrace(Path compose) throws IOException {
        Matcher matcher = STOP_GRACE.matcher(Files.readString(compose));
        List<Duration> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(DurationStyle.detectAndParse(matcher.group(1)));
        }
        assertThat(found).as("%s 의 stop_grace_period(api 서비스)", compose).hasSize(1);
        return found.get(0);
    }
}

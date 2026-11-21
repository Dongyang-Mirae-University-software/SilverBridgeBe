package kr.silverbridge.main.global.config;

import kr.silverbridge.main.domain.auth.service.AccessLogService;
import kr.silverbridge.main.domain.notification.channel.EmailSendExecutor;
import kr.silverbridge.main.global.client.SolapiCallExecutor;
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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 우아한 종료 시간 예산(2026-11-21 최종 점검 M-1 → 2026-11-22 최종 점검 I-1에서 식을 실제 순서에 맞게 고침).
 *
 * <p>종료에서 기다릴 수 있는 시간을 <b>모두 더한 값</b>이 컨테이너 grace({@code stop_grace_period})보다 짧아야 한다.
 * 넘으면 Docker가 SIGKILL을 보내 큐의 알림(SOS 포함)이 잘린다. 예전 식은 "단계 대기 + 가장 긴 풀 대기"였는데,
 * Spring 7은 풀을 빈 파괴 때 <b>하나씩 차례로</b> 기다리고(합산) 단계 대기도 단계마다 따로 걸린다(실측).</p>
 *
 * <pre>
 * 단계 대기 × 2      웹 우아한 종료 단계 + 0단계(진행 중 예약 작업·WebSocket 실행기)
 * + Tomcat 정지      요청 스레드 종료 대기 5초 + 서블릿 unload 2초(단계 대기를 넘긴 요청이 있을 때)
 * + 알림 풀 비우기    UrgentNotificationDrainLifecycle 공유 마감
 * + Σ 풀 잔여 대기    AsyncConfig 풀 전부(파괴 때 차례로)
 * + Σ 수동 풀 대기    @PreDestroy에서 자기 풀을 기다리는 클래스
 * &lt; stop_grace_period (dev·prod)
 * </pre>
 *
 * <p>컨텍스트를 띄우지 않고 설정 파일·풀 설정·상수를 직접 읽는다. 순서 자체는 {@code ShutdownOrderTest}가 본다.</p>
 */
class ShutdownGraceBudgetTest {

    private static final Path APPLICATION_YAML = Path.of("src/main/resources/application.yaml");
    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final List<Path> COMPOSE_FILES = List.of(Path.of("docker-compose.dev.yml"), Path.of("docker-compose.prod.yml"));
    private static final Pattern PHASE_TIMEOUT = Pattern.compile("(?m)^\\s+timeout-per-shutdown-phase:\\s*(\\S+)");
    private static final Pattern STOP_GRACE = Pattern.compile("(?m)^\\s+stop_grace_period:\\s*(\\S+)");

    /** 단계 대기가 통째로 걸릴 수 있는 단계 수 - 웹 우아한 종료(MAX-1024)와 0단계(스케줄러·WebSocket 실행기). */
    private static final int BLOCKING_PHASES = 2;

    /** Tomcat 기본값: 요청 스레드 종료 대기 5초(executorTerminationTimeoutMillis) + 서블릿 unload 2초(unloadDelay). */
    private static final Duration TOMCAT_STOP = Duration.ofSeconds(7);

    /** {@code @PreDestroy}에서 자기 풀이 끝나기를 기다리는 클래스와 그 대기 상수(초). 빈 파괴는 한 스레드라 더해진다. */
    private static final Map<Class<?>, String> MANUAL_POOL_WAITS = Map.of(
            SolapiCallExecutor.class, "AWAIT_TERMINATION_SECONDS",
            EmailSendExecutor.class, "AWAIT_TERMINATION_SECONDS",
            AccessLogService.class, "WRITER_SHUTDOWN_WAIT_SECONDS");

    @Test
    @DisplayName("단계 대기×2 + Tomcat 정지 + 알림 풀 비우기 + Σ풀 잔여 대기 + Σ수동 풀 대기 < api 컨테이너 stop_grace_period (dev·prod)")
    void 종료_대기_합이_컨테이너_grace보다_짧다() throws Exception {
        Duration phases = phaseTimeout().multipliedBy(BLOCKING_PHASES);
        Duration drain = Duration.ofSeconds(UrgentNotificationDrainLifecycle.DRAIN_TIMEOUT_SECONDS);
        Duration pools = sumOfPoolAwaits();
        Duration manual = sumOfManualPoolWaits();
        Duration budget = phases.plus(TOMCAT_STOP).plus(drain).plus(pools).plus(manual);

        assertThat(drain).as("알림 풀 비우기 마감").isPositive();
        assertThat(pools).as("AsyncConfig 풀 잔여 대기 합").isPositive();
        assertThat(manual).as("수동 풀 대기 합").isPositive();
        for (Path compose : COMPOSE_FILES) {
            assertThat(budget)
                    .as("%s: 단계 %s + Tomcat %s + 비우기 %s + 풀 잔여 %s + 수동 풀 %s = %s 는 stop_grace_period 보다 짧아야 한다",
                            compose, phases, TOMCAT_STOP, drain, pools, manual, budget)
                    .isLessThan(stopGrace(compose));
        }
    }

    @Test
    @DisplayName("@PreDestroy에서 풀을 기다리는 클래스는 모두 예산에 들어 있다 - 새로 생기면 MANUAL_POOL_WAITS에 넣을 것")
    void 수동_풀_대기는_빠짐없이_예산에_든다() throws IOException {
        Set<String> waiting = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (source.contains("@PreDestroy") && source.contains("awaitTermination")) {
                    waiting.add(file.getFileName().toString().replace(".java", ""));
                }
            }
        }

        assertThat(waiting)
                .as("@PreDestroy + awaitTermination 을 함께 쓰는 클래스(빈 파괴 때 대기가 더해진다)")
                .isEqualTo(MANUAL_POOL_WAITS.keySet().stream().map(Class::getSimpleName)
                        .collect(Collectors.toCollection(TreeSet::new)));
    }

    private static Duration phaseTimeout() throws IOException {
        Matcher matcher = PHASE_TIMEOUT.matcher(Files.readString(APPLICATION_YAML));
        assertThat(matcher.find())
                .as("application.yaml 에 spring.lifecycle.timeout-per-shutdown-phase 가 있어야 한다(없으면 기본 30초)")
                .isTrue();
        return DurationStyle.detectAndParse(matcher.group(1));
    }

    /** {@link AsyncConfig}가 만드는 풀 전부의 종료 대기 합 - 파괴 때 풀마다 차례로 기다리므로 더한다. */
    private static Duration sumOfPoolAwaits() throws Exception {
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
            long totalMillis = 0;
            for (ThreadPoolTaskExecutor executor : executors) {
                assertThat(ReflectionTestUtils.getField(executor, "waitForTasksToCompleteOnShutdown"))
                        .as("풀은 종료 때 큐를 비우고 끝낸다").isEqualTo(true);
                totalMillis += (long) ReflectionTestUtils.getField(executor, "awaitTerminationMillis");
            }
            return Duration.ofMillis(totalMillis);
        } finally {
            executors.forEach(ThreadPoolTaskExecutor::shutdown);
        }
    }

    private static Duration sumOfManualPoolWaits() {
        long totalSeconds = 0;
        for (Map.Entry<Class<?>, String> entry : MANUAL_POOL_WAITS.entrySet()) {
            Object seconds = ReflectionTestUtils.getField(entry.getKey(), entry.getValue());
            assertThat(seconds).as("%s.%s", entry.getKey().getSimpleName(), entry.getValue()).isInstanceOf(Number.class);
            totalSeconds += ((Number) seconds).longValue();
        }
        return Duration.ofSeconds(totalSeconds);
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

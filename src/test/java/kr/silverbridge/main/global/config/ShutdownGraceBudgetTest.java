package kr.silverbridge.main.global.config;

import kr.silverbridge.main.domain.anomaly.client.AiLiveStreamSubscriber;
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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 우아한 종료 시간 예산(2026-11-21 최종 점검 M-1 → 2026-11-22 최종 점검 I-1에서 식을 실제 순서에 맞게 고침 →
 * 같은 날 점검 S-1·T-3에서 수신 중단 대기를 더하고 종료 훅 전수를 목록으로 고정).
 *
 * <p>종료에서 기다릴 수 있는 시간을 <b>모두 더한 값</b>이 컨테이너 grace({@code stop_grace_period})보다 짧아야 한다.
 * 넘으면 Docker가 SIGKILL을 보내 큐의 알림(SOS 포함)이 잘린다. 예전 식은 "단계 대기 + 가장 긴 풀 대기"였는데,
 * Spring 7은 풀을 빈 파괴 때 <b>하나씩 차례로</b> 기다리고(합산) 단계 대기도 단계마다 따로 걸린다(실측).</p>
 *
 * <pre>
 * 단계 대기 × 2      웹 우아한 종료 단계 + 0단계(진행 중 예약 작업·WebSocket 실행기)
 * + Tomcat 정지      요청 스레드 종료 대기 5초 + 서블릿 unload 2초(단계 대기를 넘긴 요청이 있을 때)
 * + Σ 종료 훅 대기    WAITING_HOOKS - AI 수신 중단(처리 중 프레임) · 알림 풀 비우기 · 수동 풀(@PreDestroy)
 * + Σ 풀 잔여 대기    AsyncConfig 풀 전부(파괴 때 차례로)
 * &lt; stop_grace_period (dev·prod)
 * </pre>
 *
 * <p>종료 훅({@code @PreDestroy}·{@code Lifecycle}·{@code DisposableBean} 등)을 가진 클래스는 <b>전부</b> 아래 두 목록 중
 * 하나에 있어야 한다. 예전 검사는 {@code @PreDestroy}와 {@code awaitTermination}이 한 파일에 있는 경우만 봐서, 다른 방식의
 * 대기(helper 경유, {@code ExecutorService.close()}, {@code DisposableBean})가 예산 밖에서 생겨도 알 수 없었다. 이제는 새 훅이
 * 생기면 사람이 대기 시간을 적기 전까지 실패한다 - helper를 거친 대기는 문자열로 못 잡으므로 이 선언이 방어선이다.</p>
 *
 * <p>컨텍스트를 띄우지 않고 설정 파일·풀 설정·상수·소스를 직접 읽는다. 순서 자체는 {@code ShutdownOrderTest}가 본다.</p>
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

    /**
     * 종료 훅에서 <b>기다리는</b> 클래스와 그 대기 상수(초). 종료 단계의 {@code stop()}과 빈 파괴는 한 스레드에서 차례로
     * 돌아 모두 더해진다. 값은 상수에서 읽으므로 상수를 키우면 예산도 함께 커진다.
     */
    private static final Map<Class<?>, String> WAITING_HOOKS = Map.of(
            AiLiveStreamSubscriber.class, "IN_FLIGHT_WAIT_SECONDS",              // 처리 중이던 프레임(첫 stop에서만)
            UrgentNotificationDrainLifecycle.class, "DRAIN_TIMEOUT_SECONDS",     // 긴급·일반 알림 풀 비우기
            SolapiCallExecutor.class, "AWAIT_TERMINATION_SECONDS",
            EmailSendExecutor.class, "AWAIT_TERMINATION_SECONDS",
            AccessLogService.class, "WRITER_SHUTDOWN_WAIT_SECONDS");

    /** 종료 훅은 있지만 <b>기다리지 않는</b>(0초) 클래스 - 연결을 닫거나 {@code shutdownNow()}만 한다. 본문은 따로 확인한다. */
    private static final Set<String> NO_WAIT_HOOKS = Set.of(
            "CameraStreamSlots",          // stop(): 열린 영상 중계의 AI 연결 닫기
            "LiveAnalysisBroadcaster",
            "AiClipClient",
            "AiStreamClient",
            "AiChatClient",
            "AiSttClient",
            "AiCompanionClient",
            "CompanionAiStatusClient",
            "AiGameClient",
            "AiMedicationOcrClient",
            "ReservationAdminClient",
            "AiServerHealthClient",
            "AdminAiStatusService");

    /** 이 중 하나라도 있으면 "종료 때 불리는 훅을 가진 클래스"로 본다. 주석에만 나와도 잡힌다(놓치는 것보다 낫다). */
    private static final List<Pattern> HOOK_MARKERS = List.of(
            Pattern.compile("(?m)^\\s*@PreDestroy\\b"),
            Pattern.compile("\\bimplements\\b[^{]*\\b(?:Smart)?Lifecycle\\b"),
            Pattern.compile("\\bimplements\\b[^{]*\\bDisposableBean\\b"),
            Pattern.compile("\\bdestroyMethod\\b"),
            // 최상위 클래스가 AutoCloseable이면 Spring이 close()를 파괴 메서드로 부른다(안쪽 클래스는 빈이 아니다)
            Pattern.compile("(?m)^(?:public\\s+)?(?:(?:final|abstract)\\s+)*(?:class|record|enum)\\s+\\w+[^{]*"
                    + "\\bimplements\\b[^{]*\\b(?:Auto)?Closeable\\b"),
            Pattern.compile("\\bContextClosedEvent\\b"));

    /** 클래스 멤버 메서드의 시작 줄(들여쓰기 4칸, 한 줄 시그니처). */
    private static final Pattern MEMBER_METHOD_START = Pattern.compile("^ {4}\\S[^;]*\\)\\s*(?:throws [\\w.,\\s]+)?\\{\\s*$");

    /** 애너테이션 없이도 종료 때 불리는 메서드 - {@code Lifecycle.stop}·{@code DisposableBean.destroy}·{@code AutoCloseable.close}. */
    private static final Pattern NAMED_HOOK_METHOD = Pattern.compile(
            "^ {4}(?:public |protected |private )?(?:synchronized )?void (?:stop\\((?:Runnable\\s+\\w+)?\\)|destroy\\(\\)|close\\(\\))");

    /** 0초로 선언한 훅의 본문에 있으면 안 되는 호출 - 스레드·작업이 끝나기를 기다리는 것들. */
    private static final List<String> WAIT_CALLS = List.of(
            "awaitTermination", ".close()", ".join(", ".get(", "sleep(", ".await(", "invokeAll", "awaitQuiescence");

    @Test
    @DisplayName("단계 대기×2 + Tomcat 정지 + Σ종료 훅 대기(수신 중단·알림 풀 비우기·수동 풀) + Σ풀 잔여 대기 < api 컨테이너 stop_grace_period (dev·prod)")
    void 종료_대기_합이_컨테이너_grace보다_짧다() throws Exception {
        Duration phases = phaseTimeout().multipliedBy(BLOCKING_PHASES);
        Duration hooks = sumOfHookWaits();
        Duration pools = sumOfPoolAwaits();
        Duration budget = phases.plus(TOMCAT_STOP).plus(hooks).plus(pools);

        assertThat(pools).as("AsyncConfig 풀 잔여 대기 합").isPositive();
        for (Path compose : COMPOSE_FILES) {
            assertThat(budget)
                    .as("%s: 단계 %s + Tomcat %s + 종료 훅 %s + 풀 잔여 %s = %s 는 stop_grace_period 보다 짧아야 한다",
                            compose, phases, TOMCAT_STOP, hooks, pools, budget)
                    .isLessThan(stopGrace(compose));
        }
    }

    @Test
    @DisplayName("종료 훅을 가진 클래스는 모두 대기 시간이 선언돼 있다 - 새로 생기면 WAITING_HOOKS(초) 또는 NO_WAIT_HOOKS(0초)에 넣을 것")
    void 종료_훅_클래스는_빠짐없이_선언돼_있다() throws IOException {
        Set<String> waiting = WAITING_HOOKS.keySet().stream().map(Class::getSimpleName)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> declared = new TreeSet<>(waiting);
        declared.addAll(NO_WAIT_HOOKS);

        assertThat(waiting).as("한 클래스가 두 목록에 함께 있으면 안 된다").doesNotContainAnyElementsOf(NO_WAIT_HOOKS);
        assertThat(new TreeSet<>(hookHolders().keySet()))
                .as("종료 훅(@PreDestroy·Lifecycle·DisposableBean·destroyMethod·AutoCloseable·ContextClosedEvent)을 가진 클래스")
                .isEqualTo(declared);
    }

    @Test
    @DisplayName("0초로 선언한 종료 훅의 본문에는 기다리는 호출이 없다 - 기다리게 바꾸면 WAITING_HOOKS로 옮기고 상수를 둘 것")
    void 기다리지_않는다고_선언한_훅은_실제로_기다리지_않는다() throws IOException {
        Map<String, String> holders = hookHolders();
        for (String name : NO_WAIT_HOOKS) {
            assertThat(holders).as("NO_WAIT_HOOKS의 %s는 종료 훅을 가진 클래스여야 한다", name).containsKey(name);
            List<String> bodies = hookBodies(holders.get(name));

            assertThat(bodies).as("%s의 종료 훅 본문을 찾지 못했다 - 검사 방식을 넓힐 것", name).isNotEmpty();
            for (String body : bodies) {
                for (String waitCall : WAIT_CALLS) {
                    assertThat(body).as("%s의 종료 훅 본문", name).doesNotContain(waitCall);
                }
            }
        }
    }

    /** 종료 훅을 가진 클래스(파일 이름) → 소스. */
    private static Map<String, String> hookHolders() throws IOException {
        Map<String, String> holders = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (HOOK_MARKERS.stream().anyMatch(marker -> marker.matcher(source).find())) {
                    String previous = holders.put(file.getFileName().toString().replace(".java", ""), source);
                    assertThat(previous).as("같은 이름의 클래스가 둘이면 이름으로 구분할 수 없다: %s", file).isNull();
                }
            }
        }
        return holders;
    }

    /** 종료 때 불리는 메서드의 본문들 - {@code @PreDestroy}가 붙은 메서드와 {@code stop()}·{@code destroy()}·{@code close()}. */
    private static List<String> hookBodies(String source) {
        List<String> bodies = new ArrayList<>();
        String[] lines = source.split("\n");
        boolean annotated = false;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].strip().equals("@PreDestroy")) {
                annotated = true;
                continue;
            }
            boolean hook = annotated || NAMED_HOOK_METHOD.matcher(lines[i]).find();
            if (hook && MEMBER_METHOD_START.matcher(lines[i]).find()) {
                StringBuilder body = new StringBuilder();
                for (i++; i < lines.length && !lines[i].stripTrailing().equals("    }"); i++) {
                    body.append(lines[i]).append('\n');
                }
                bodies.add(body.toString());
                annotated = false;
            }
        }
        return bodies;
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

    /** 기다리는 종료 훅의 대기 합. 0 이하인 상수는 "기다린다"는 선언과 어긋나므로 실패시킨다. */
    private static Duration sumOfHookWaits() {
        long totalSeconds = 0;
        for (Map.Entry<Class<?>, String> entry : WAITING_HOOKS.entrySet()) {
            Object seconds = ReflectionTestUtils.getField(entry.getKey(), entry.getValue());
            assertThat(seconds).as("%s.%s", entry.getKey().getSimpleName(), entry.getValue()).isInstanceOf(Number.class);
            assertThat(((Number) seconds).longValue())
                    .as("%s.%s (초)", entry.getKey().getSimpleName(), entry.getValue()).isPositive();
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

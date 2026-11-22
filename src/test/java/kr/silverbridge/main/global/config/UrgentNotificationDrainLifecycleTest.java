package kr.silverbridge.main.global.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 종료 단계의 알림 풀 비우기(2026-11-22 최종 점검 I-1·I-3): 풀이 빌 때까지 기다리되 마감을 넘기지 않고, 풀을 닫지 않는다.
 * 단계 순서는 {@link ShutdownOrderTest}, 마감 값과 컨테이너 grace의 관계는 {@link ShutdownGraceBudgetTest}가 본다.
 */
class UrgentNotificationDrainLifecycleTest {

    private final AsyncConfig config = new AsyncConfig();
    private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    private ThreadPoolTaskExecutor urgent;
    private ThreadPoolTaskExecutor general;

    @BeforeEach
    void setUp() {
        urgent = (ThreadPoolTaskExecutor) config.urgentNotificationExecutor();
        general = (ThreadPoolTaskExecutor) config.notificationExecutor();
        beanFactory.registerSingleton("urgentNotificationExecutor", urgent);
        beanFactory.registerSingleton("notificationExecutor", general);
    }

    @AfterEach
    void tearDown() {
        urgent.getThreadPoolExecutor().shutdownNow();
        general.getThreadPoolExecutor().shutdownNow();
    }

    @Test
    @DisplayName("풀이 비어 있으면 곧바로 끝난다 - 평소 배포를 늦추지 않는다")
    void 빈_풀은_기다리지_않는다() {
        UrgentNotificationDrainLifecycle drain = new UrgentNotificationDrainLifecycle(beanFactory, Duration.ofSeconds(10));
        drain.start();

        long started = System.nanoTime();
        drain.stop();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(drain.isRunning()).isFalse();
    }

    @Test
    @DisplayName("긴급 풀과 일반 풀의 실행 중·대기 중 작업이 모두 끝날 때까지 기다린다")
    void 실행중_작업과_큐를_기다린다() throws Exception {
        CountDownLatch urgentStarted = new CountDownLatch(4);
        AtomicBoolean queuedUrgentDone = new AtomicBoolean();
        AtomicBoolean generalDone = new AtomicBoolean();
        // 긴급 풀 core 4개를 모두 잡아 다섯 번째 작업이 큐에 서게 한다 - 큐에 있는 것도 기다리는지 본다.
        for (int i = 0; i < 4; i++) {
            urgent.execute(() -> {
                urgentStarted.countDown();
                sleep(300);
            });
        }
        urgent.execute(() -> queuedUrgentDone.set(true));
        general.execute(() -> {
            sleep(500);
            generalDone.set(true);
        });
        assertThat(urgentStarted.await(5, TimeUnit.SECONDS)).isTrue();

        new UrgentNotificationDrainLifecycle(beanFactory, Duration.ofSeconds(10)).stop();

        assertThat(queuedUrgentDone).as("큐에 서 있던 긴급 작업").isTrue();
        assertThat(generalDone).as("일반 풀의 실행 중 작업").isTrue();
    }

    @Test
    @DisplayName("마감을 넘기면 돌아온다 - 그래도 풀은 닫지 않아 이후 작업을 계속 받는다(남은 것은 파괴 때 잔여 대기가 맡는다)")
    void 마감을_넘기면_포기하되_풀은_열어_둔다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stuckStarted = new CountDownLatch(1);
        urgent.execute(() -> {
            stuckStarted.countDown();
            await(release);
        });
        assertThat(stuckStarted.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        new UrgentNotificationDrainLifecycle(beanFactory, Duration.ofMillis(300)).stop();
        Duration waited = Duration.ofNanos(System.nanoTime() - started);

        assertThat(waited).isBetween(Duration.ofMillis(300), Duration.ofSeconds(3));
        assertThat(urgent.getThreadPoolExecutor().isShutdown()).as("긴급 풀을 닫지 않는다").isFalse();
        assertThat(general.getThreadPoolExecutor().isShutdown()).as("일반 풀을 닫지 않는다").isFalse();

        CountDownLatch lateRan = new CountDownLatch(1);
        urgent.execute(lateRan::countDown);
        assertThat(lateRan.await(5, TimeUnit.SECONDS)).as("비우기 뒤에 들어온 긴급 작업도 실행된다").isTrue();
        release.countDown();
    }

    @Test
    @DisplayName("풀을 찾지 못해도 종료를 막지 않는다")
    void 풀이_없어도_예외_없이_끝난다() {
        UrgentNotificationDrainLifecycle drain =
                new UrgentNotificationDrainLifecycle(new DefaultListableBeanFactory(), Duration.ofSeconds(10));

        long started = System.nanoTime();
        drain.stop();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("비울 풀 이름은 AsyncConfig의 긴급·일반 알림 풀이다 - 이름이 어긋나면 종료 때 아무것도 기다리지 않게 된다")
    void 비울_풀_이름() {
        assertThat(UrgentNotificationDrainLifecycle.EXECUTOR_BEAN_NAMES)
                .containsExactly("urgentNotificationExecutor", "notificationExecutor");
    }

    @Test
    @DisplayName("운영에서 쓰는 기본 생성자의 마감은 상수(예산에 들어가는 값)이고, 수신자 1명의 FCM 13초 + 문자 폴백 10초를 덮는다")
    void 기본_생성자의_마감은_상수와_같다() {
        UrgentNotificationDrainLifecycle drain = new UrgentNotificationDrainLifecycle(beanFactory);

        assertThat(ReflectionTestUtils.getField(drain, "timeout"))
                .isEqualTo(Duration.ofSeconds(UrgentNotificationDrainLifecycle.DRAIN_TIMEOUT_SECONDS));
        assertThat(UrgentNotificationDrainLifecycle.DRAIN_TIMEOUT_SECONDS).isGreaterThanOrEqualTo(23);
    }

    @Test
    @DisplayName("한 번 비어 보였다고 끝내지 않는다 - 연속 두 번 비어야 끝나고, 사이에 바쁨을 보면 처음부터 다시 센다")
    void 연속_두_번_비어야_끝난다() {
        // 긴급 풀이 답하는 실행 중 작업 수. 0 = 비어 보임(큐에서 꺼낸 직후의 틈일 수 있다), 1 = 실행 중.
        // 올바른 구현은 마지막 [0, 0]에서야 끝난다. 한 번만 확인하면 첫 0에서, 바쁨을 보고도 횟수를 지우지 않으면 세 번째에서 끝난다.
        List<Integer> script = List.of(0, 1, 0, 1, 0, 0);
        AtomicInteger probes = new AtomicInteger();
        ThreadPoolExecutor scripted = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()) {
            @Override
            public int getActiveCount() {
                int index = probes.getAndIncrement();
                return index < script.size() ? script.get(index) : 0;
            }
        };
        ThreadPoolTaskExecutor scriptedUrgent = new ThreadPoolTaskExecutor() {
            @Override
            public ThreadPoolExecutor getThreadPoolExecutor() {
                return scripted;
            }
        };
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("urgentNotificationExecutor", scriptedUrgent);
        factory.registerSingleton("notificationExecutor", general);

        try {
            new UrgentNotificationDrainLifecycle(factory, Duration.ofSeconds(10)).stop();

            assertThat(probes.get())
                    .as("각본의 마지막 [0, 0]까지 확인한 뒤에 끝나야 한다(각본 %d칸)", script.size())
                    .isGreaterThanOrEqualTo(script.size());
        } finally {
            scripted.shutdownNow();
        }
    }

    @Test
    @DisplayName("마감을 넘기면 WARN을 남긴다 - 남은 작업 수만 적고, 비웠다는 INFO는 남기지 않는다")
    void 마감_초과는_WARN으로_남는다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stuckStarted = new CountDownLatch(1);
        urgent.execute(() -> {
            stuckStarted.countDown();
            await(release);
        });
        assertThat(stuckStarted.await(5, TimeUnit.SECONDS)).isTrue();
        ListAppender<ILoggingEvent> logs = attachLogs();
        try {
            new UrgentNotificationDrainLifecycle(beanFactory, Duration.ofMillis(300)).stop();

            assertThat(logs.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .startsWith("[SHUTDOWN-DRAIN] 알림 풀을 마감 안에 비우지 못함")
                        .contains("limitMs=300", "urgentNotificationExecutor(active=1, queue=0)");
            });
        } finally {
            detachLogs(logs);
            release.countDown();
        }
    }

    @Test
    @DisplayName("기다리는 중에 인터럽트가 걸리면 곧바로 돌아오고 인터럽트 표시를 되살린다 - 종료 스레드가 멈춤 요청을 잃지 않게")
    void 인터럽트가_걸리면_표시를_되살리고_돌아온다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stuckStarted = new CountDownLatch(1);
        urgent.execute(() -> {
            stuckStarted.countDown();
            await(release);
        });
        assertThat(stuckStarted.await(5, TimeUnit.SECONDS)).isTrue();
        UrgentNotificationDrainLifecycle drain = new UrgentNotificationDrainLifecycle(beanFactory, Duration.ofSeconds(10));
        AtomicBoolean interruptedAfterStop = new AtomicBoolean();
        // 테스트 스레드에 인터럽트를 걸면 표시가 남아 뒤따르는 테스트가 깨질 수 있어, 종료 스레드 역할을 따로 둔다.
        Thread stopper = new Thread(() -> {
            drain.stop();
            interruptedAfterStop.set(Thread.currentThread().isInterrupted());
        }, "test-drain-stop");
        stopper.setDaemon(true);

        try {
            stopper.start();
            sleep(150);   // 대기 루프에 들어간 뒤에 건다(더 일찍 걸려도 첫 대기에서 같은 길로 빠진다)
            long interruptedAt = System.nanoTime();
            stopper.interrupt();
            stopper.join(5_000);

            assertThat(stopper.isAlive()).as("마감(10초)까지 기다리지 않고 돌아온다").isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - interruptedAt)).isLessThan(Duration.ofSeconds(2));
            assertThat(interruptedAfterStop).as("인터럽트 표시를 되살린다").isTrue();
        } finally {
            release.countDown();
        }
    }

    private static ListAppender<ILoggingEvent> attachLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        drainLogger().addAppender(appender);
        return appender;
    }

    private static void detachLogs(ListAppender<ILoggingEvent> appender) {
        drainLogger().detachAppender(appender);
    }

    private static Logger drainLogger() {
        return (Logger) LoggerFactory.getLogger(UrgentNotificationDrainLifecycle.class);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

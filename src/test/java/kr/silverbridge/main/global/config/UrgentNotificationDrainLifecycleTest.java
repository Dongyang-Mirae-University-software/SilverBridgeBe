package kr.silverbridge.main.global.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

package kr.silverbridge.main.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * 알림 executor 정책 고정 (2026-09-11 기술 점검 B-2·B-3 / 긴급 풀 분리 2026-10-02 QA SOS-G13·XCUT-G11).
 *
 * <p>포화 시 호출 스레드에서 실행하지 않아야 한다 - 그러면 AI WS 수신 스레드가 FCM·SMS 응답을 기다린다.
 * 대신 예외 없이 폐기하고 로그만 남긴다. 종료 시에는 큐를 비울 시간을 기다린다. 긴급 풀도 같은 정책이다.</p>
 */
class AsyncConfigTest {

    static Stream<Arguments> executors() {
        Function<AsyncConfig, Object> general = AsyncConfig::notificationExecutor;
        Function<AsyncConfig, Object> urgent = AsyncConfig::urgentNotificationExecutor;
        return Stream.of(Arguments.of("notificationExecutor", general),
                Arguments.of("urgentNotificationExecutor", urgent));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executors")
    @DisplayName("포화 시 호출 스레드로 넘기지도, 예외를 던지지도 않는다 - 폐기하고 로그만 남긴다")
    void 포화시_호출스레드_실행_없음(String name, Function<AsyncConfig, Object> factory) throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) factory.apply(new AsyncConfig());
        CountDownLatch release = new CountDownLatch(1);
        int capacity = executor.getMaxPoolSize() + executor.getQueueCapacity();
        String callerThread = Thread.currentThread().getName();
        boolean[] ranOnCaller = {false};
        try {
            for (int i = 0; i < capacity; i++) {
                executor.execute(() -> await(release));
            }
            // 이 한 건이 거부 대상이다. CallerRunsPolicy였다면 여기서 현재 스레드가 await에 갇힌다.
            assertThatNoException().isThrownBy(() -> executor.execute(() -> {
                if (Thread.currentThread().getName().equals(callerThread)) {
                    ranOnCaller[0] = true;
                }
            }));
            assertThat(ranOnCaller[0]).isFalse();
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isNotInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class)
                    .isNotInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("일반 풀 - 크기와 종료 대기(배포 순간 큐의 알림이 사라지지 않게)")
    void 종료시_대기() {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig().notificationExecutor();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(10);
            assertThat(executor.getQueueCapacity()).isEqualTo(500);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("notify-");
            assertThat(executor).extracting("waitForTasksToCompleteOnShutdown").isEqualTo(true);
            assertThat(executor).extracting("awaitTerminationMillis").isEqualTo(20_000L);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("긴급 풀 - 일반 풀과 독립된 풀이고, 크기·스레드 이름·종료 대기가 고정된다")
    void 긴급풀_구성() {
        AsyncConfig config = new AsyncConfig();
        ThreadPoolTaskExecutor urgent = (ThreadPoolTaskExecutor) config.urgentNotificationExecutor();
        ThreadPoolTaskExecutor general = (ThreadPoolTaskExecutor) config.notificationExecutor();
        try {
            assertThat(urgent.getThreadPoolExecutor()).isNotSameAs(general.getThreadPoolExecutor());
            assertThat(urgent.getCorePoolSize()).isEqualTo(4);
            assertThat(urgent.getMaxPoolSize()).isEqualTo(8);
            assertThat(urgent.getQueueCapacity()).isEqualTo(200);
            assertThat(urgent.getThreadNamePrefix()).isEqualTo("notify-urgent-");
            assertThat(urgent).extracting("waitForTasksToCompleteOnShutdown").isEqualTo(true);
            assertThat(urgent).extracting("awaitTerminationMillis").isEqualTo(20_000L);
        } finally {
            urgent.shutdown();
            general.shutdown();
        }
    }

    @Test
    @DisplayName("일반 풀이 포화돼도 긴급 풀 작업은 바로 실행된다")
    void 일반풀_포화와_무관하게_긴급풀_실행() throws Exception {
        AsyncConfig config = new AsyncConfig();
        ThreadPoolTaskExecutor general = (ThreadPoolTaskExecutor) config.notificationExecutor();
        ThreadPoolTaskExecutor urgent = (ThreadPoolTaskExecutor) config.urgentNotificationExecutor();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch urgentRan = new CountDownLatch(1);
        try {
            int capacity = general.getMaxPoolSize() + general.getQueueCapacity();
            for (int i = 0; i < capacity; i++) {
                general.execute(() -> await(release));
            }
            urgent.execute(urgentRan::countDown);
            assertThat(urgentRan.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            general.shutdown();
            urgent.shutdown();
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

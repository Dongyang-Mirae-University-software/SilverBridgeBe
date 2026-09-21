package kr.silverbridge.main.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * 알림 executor 정책 고정 (2026-09-11 기술 점검 B-2·B-3).
 *
 * <p>포화 시 호출 스레드에서 실행하지 않아야 한다 - 그러면 AI WS 수신 스레드가 FCM·SMS 응답을 기다린다.
 * 대신 예외 없이 폐기하고 로그만 남긴다. 종료 시에는 큐를 비울 시간을 기다린다.</p>
 */
class AsyncConfigTest {

    @Test
    @DisplayName("포화 시 호출 스레드로 넘기지도, 예외를 던지지도 않는다 - 폐기하고 로그만 남긴다")
    void 포화시_호출스레드_실행_없음() throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig().notificationExecutor();
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
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("종료 시 큐에 남은 작업을 기다린다 - 배포 순간 SOS 알림이 사라지지 않게")
    void 종료시_대기() {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig().notificationExecutor();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(10);
            assertThat(executor.getQueueCapacity()).isEqualTo(500);
            assertThat(executor).extracting("waitForTasksToCompleteOnShutdown").isEqualTo(true);
            assertThat(executor).extracting("awaitTerminationMillis").isEqualTo(20_000L);
        } finally {
            executor.shutdown();
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

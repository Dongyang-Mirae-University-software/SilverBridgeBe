package kr.silverbridge.main.domain.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmailSendExecutorTest {

    @Test
    @DisplayName("제한 안에 끝나면 true")
    void 정상() {
        EmailSendExecutor executor = new EmailSendExecutor(Duration.ofSeconds(2));
        AtomicInteger ran = new AtomicInteger();

        assertThat(executor.run(ran::incrementAndGet)).isTrue();
        assertThat(ran).hasValue(1);
    }

    @Test
    @DisplayName("작업이 던진 런타임 예외는 그대로 다시 던진다 (MailException 처리를 호출자가 한다)")
    void 예외전파() {
        EmailSendExecutor executor = new EmailSendExecutor(Duration.ofSeconds(2));

        assertThatThrownBy(() -> executor.run(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("풀과 큐가 꽉 차면 기다리지 않고 즉시 false (CallerRuns 아님)")
    void 포화() throws InterruptedException {
        EmailSendExecutor executor = new EmailSendExecutor(Duration.ofMillis(200));
        CountDownLatch release = new CountDownLatch(1);
        int capacity = EmailSendExecutor.POOL_SIZE + EmailSendExecutor.QUEUE_CAPACITY;
        try {
            // 제한(200ms)을 넘겨 false가 되더라도 작업은 풀에 남아 자리를 차지한다.
            for (int i = 0; i < capacity; i++) {
                executor.run(() -> {
                    // 인터럽트(cancel(true))를 무시하고 자리를 계속 차지하는 SMTP 호출을 흉내 낸다
                    while (release.getCount() > 0) {
                        try {
                            release.await();
                        } catch (InterruptedException ignored) {
                            // 계속 대기
                        }
                    }
                });
            }
            long started = System.nanoTime();
            boolean result = executor.run(() -> { });
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

            assertThat(result).isFalse();
            assertThat(elapsedMillis).isLessThan(150);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}

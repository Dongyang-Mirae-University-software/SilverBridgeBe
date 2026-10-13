package kr.silverbridge.main.domain.notification.channel;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 이메일(SMTP) 호출에 <b>전체 시간 제한</b>을 거는 전용 실행기 (2026-10-13 점검 M-1).
 *
 * <p>JavaMail의 연결·읽기·쓰기 제한은 각각 따로라 응답이 조금씩 오면 호출 전체는 제한을 훨씬 넘길 수 있다.
 * 복약 미복용 요약은 스케줄러 스레드(풀 3, AI WS 재접속과 공유)에서 대상마다 순차 발송하므로, SMTP가 느려지면
 * 그 스레드가 오래 묶인다. 호출자는 {@code notification.email.call-timeout-seconds}만 기다린다(Solapi
 * {@code SolapiCallExecutor}와 같은 방식).</p>
 *
 * <p>풀은 데몬 스레드 4개 + 큐 10이고, 꽉 차면 기다리지 않고 실패로 돌려준다(SMTP가 이미 묶여 있다는 뜻이라
 * 줄을 세우면 호출자만 다시 묶인다). CallerRuns는 쓰지 않는다(호출 스레드에서 돌리면 시간 제한이 사라진다).
 * 시간 초과 건이 실제로는 접수됐을 수 있으나 결과를 모르므로 실패로 기록한다. 로그에는 클래스명만 남긴다.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "notification.email.enabled", havingValue = "true")
public class EmailSendExecutor {

    static final int POOL_SIZE = 4;
    static final int QUEUE_CAPACITY = 10;
    private static final int AWAIT_TERMINATION_SECONDS = 5;

    private final Duration timeout;
    private final ThreadPoolExecutor pool;

    @Autowired
    public EmailSendExecutor(@Value("${notification.email.call-timeout-seconds:10}") long timeoutSeconds) {
        this(Duration.ofSeconds(timeoutSeconds));
    }

    EmailSendExecutor(Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("notification.email.call-timeout-seconds는 1 이상이어야 합니다: " + timeout);
        }
        this.timeout = timeout;
        AtomicInteger seq = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(POOL_SIZE, POOL_SIZE, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "email-send-" + seq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 작업을 시간 제한 안에서 실행한다.
     *
     * @return 제한 안에 끝났으면 true. 시간 초과·풀 포화·대기 중 인터럽트면 false(호출자가 실패로 처리)
     * @throws RuntimeException 작업이 던진 런타임 예외는 그대로 다시 던진다
     */
    public boolean run(Runnable task) {
        Future<?> future;
        try {
            future = pool.submit(task);
        } catch (RejectedExecutionException e) {
            log.error("[EMAIL-REJECTED] 이메일 호출 풀 포화로 발송 실패 처리: active={}, queue={}",
                    pool.getActiveCount(), pool.getQueue().size());
            return false;
        }
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("[EMAIL-TIMEOUT] 이메일 호출이 {}초 안에 끝나지 않아 실패 처리", timeout.toSeconds());
            return false;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("[EMAIL-INTERRUPTED] 이메일 호출 대기 중 인터럽트");
            return false;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            log.error("[EMAIL-ERROR] 이메일 호출 실패: cause={}",
                    cause == null ? "unknown" : cause.getClass().getSimpleName());
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(AWAIT_TERMINATION_SECONDS, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

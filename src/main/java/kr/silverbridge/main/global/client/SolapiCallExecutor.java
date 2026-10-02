package kr.silverbridge.main.global.client;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Solapi SDK 호출에 시간 제한을 거는 전용 실행기 (2026-10-02 QA P16).
 *
 * <p><b>왜 필요한가</b>: SDK 1.0.3은 연결·읽기·쓰기 제한이 50초로 고정돼 있어({@code SolapiClient.createInstance}로
 * 바꿀 수 없다) Solapi가 느리면 호출 스레드가 최대 50초 묶인다. SOS SMS 폴백·알림톡은 긴급 알림 executor에서 돌므로
 * 그동안 뒤따르는 SOS·화재 알림이 밀린다. 그래서 SDK 호출을 이 전용 풀에서 실행하고 호출자는
 * {@code solapi.call-timeout-seconds}(기본 10초)만 기다린다.</p>
 *
 * <p><b>한계</b>: 제한을 넘기면 {@code cancel(true)}로 인터럽트하지만, SDK가 인터럽트에 반응하지 않으면 이 풀의
 * 스레드는 SDK 자체 제한(50초)까지 남는다. 그래도 <b>호출자(알림·HTTP 스레드)는 제한 시간에 풀려난다</b> - 이것이
 * 이 클래스의 목적이다. 시간 초과 건이 실제로 접수됐을 수도 있지만(늦은 응답) 결과를 모르므로 실패로 기록한다.</p>
 *
 * <p><b>풀 정책</b>: 데몬 스레드 10개 고정 + 큐 20. 꽉 차면 기다리지 않고 바로 실패로 돌려준다(AbortPolicy) -
 * 그 상태는 Solapi가 이미 묶여 있다는 뜻이라, 줄을 세우면 호출자만 다시 묶인다. CallerRuns는 쓰지 않는다
 * (호출 스레드에서 SDK를 돌리면 시간 제한이 사라진다).</p>
 *
 * <p>로그에는 작업 이름과 예외 클래스명만 남긴다 - 전화번호·예외 원문을 싣지 않는다.</p>
 */
@Slf4j
@Component
public class SolapiCallExecutor {

    static final int POOL_SIZE = 10;
    static final int QUEUE_CAPACITY = 20;
    private static final int AWAIT_TERMINATION_SECONDS = 5;

    private final Duration timeout;
    private final ThreadPoolExecutor pool;

    @Autowired
    public SolapiCallExecutor(SolapiCallProperties properties) {
        this(Duration.ofSeconds(properties.getCallTimeoutSeconds()));
    }

    SolapiCallExecutor(Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("solapi.call-timeout-seconds는 1 이상이어야 합니다: " + timeout);
        }
        this.timeout = timeout;
        AtomicInteger seq = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(POOL_SIZE, POOL_SIZE, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "solapi-call-" + seq.incrementAndGet());
                    thread.setDaemon(true);   // SDK가 인터럽트를 무시해도 JVM 종료를 막지 않는다
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * SDK 호출을 시간 제한 안에서 실행한다.
     *
     * @param name 로그용 작업 이름(예: "SMS", "알림톡") - 수신 번호를 넣지 말 것
     * @return 결과. 시간 초과·풀 포화·대기 중 인터럽트면 빈 값(호출자가 통신 오류로 처리)
     * @throws RuntimeException 작업이 던진 런타임 예외는 그대로 다시 던진다(기존 동작 보존)
     */
    public <T> Optional<T> call(String name, Callable<T> task) {
        Future<T> future;
        try {
            future = pool.submit(task);
        } catch (RejectedExecutionException e) {
            log.error("[SOLAPI-REJECTED] Solapi 호출 풀 포화로 발송 실패 처리: task={}, active={}, queue={}",
                    name, pool.getActiveCount(), pool.getQueue().size());
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("[SOLAPI-TIMEOUT] Solapi 호출이 {}초 안에 끝나지 않아 실패 처리: task={}",
                    timeout.toSeconds(), name);
            return Optional.empty();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("[SOLAPI-INTERRUPTED] Solapi 호출 대기 중 인터럽트: task={}", name);
            return Optional.empty();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            log.error("[SOLAPI-ERROR] Solapi 호출 실패: task={}, cause={}",
                    name, cause == null ? "unknown" : cause.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 우아한 종료 - 진행 중인 발송을 잠깐 기다리고, 남은 것은 인터럽트한다(데몬이라 종료를 막지는 않는다). */
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

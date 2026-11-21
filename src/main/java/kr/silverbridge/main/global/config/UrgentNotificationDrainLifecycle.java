package kr.silverbridge.main.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 종료 때 긴급·일반 알림 풀을 <b>발송 수단이 모두 살아 있는 동안</b> 비운다(2026-11-22 최종 점검 I-1·I-2·I-3).
 *
 * <p>Spring 7의 {@code ThreadPoolTaskExecutor}는 {@code waitForTasksToCompleteOnShutdown=true}면 종료 단계에서는
 * 아무것도 하지 않고 빈 파괴 때에야 풀마다 차례로 기다린다. 그 시점에는 Redis가 이미 멈췄고(0단계) AI 수신기는 아직
 * 열려 있었다(실측). 그래서 풀이 비기를 기다리는 일을 종료 단계({@link ShutdownPhases#NOTIFICATION_DRAIN})로 옮긴다 -
 * 이 단계에서는 Redis·DB·FCM·Solapi·메일이 모두 살아 있고, 웹 서버와 AI 수신기는 이미 멈췄다.</p>
 *
 * <p><b>풀을 닫지 않는다.</b> 실행 중 작업과 큐가 0이 되기를 기다릴 뿐이라, 이 단계 뒤에 늦게 들어온 작업(진행 중이던
 * 예약 작업이 낸 알림 등)도 빈 파괴 전까지 받는다. 남은 것은 {@link AsyncConfig}의 풀별 잔여 대기가 맡는다.</p>
 *
 * <p>대기는 두 풀이 함께 쓰는 마감 하나다({@link #DRAIN_TIMEOUT_SECONDS}) - 풀마다 더하지 않는다. {@code stop()}이
 * 동기라 {@code spring.lifecycle.timeout-per-shutdown-phase}와 무관하게 이 마감만 적용된다. 이 값을 바꾸면
 * {@code ShutdownGraceBudgetTest}가 컨테이너 grace와 다시 맞춰 본다.</p>
 *
 * <p><b>실행기 빈을 주입받지 않는다</b>: 주입하면 이 빈이 실행기의 의존 빈으로 등록되고, Spring은 실행기를 멈출 때
 * 의존 빈을 먼저 멈춘다({@code DefaultLifecycleProcessor.doStop}). 그러면 이 빈이 제 단계가 아니라 실행기 단계에서
 * 불려 순서가 설정과 달라진다. 이름으로 그때 조회한다({@code ShutdownOrderTest}가 고정).</p>
 */
@Slf4j
@Component
public class UrgentNotificationDrainLifecycle implements SmartLifecycle {

    /**
     * 긴급·일반 알림 풀이 비기를 기다리는 공유 마감(초). 수신자 1명에게 FCM 응답 제한(연결 3초 + 응답 10초)과
     * 문자 폴백 제한(10초)을 모두 채운 23초를 덮는 값이다. 수신자가 여럿이고 발송사가 모두 느린 경우까지는 덮지 못한다(수용).
     */
    static final int DRAIN_TIMEOUT_SECONDS = 25;

    /** 비울 풀(빈 이름). 긴급이 먼저지만 두 풀은 같은 마감 안에서 함께 비워진다. */
    static final List<String> EXECUTOR_BEAN_NAMES = List.of("urgentNotificationExecutor", "notificationExecutor");

    private static final long POLL_MILLIS = 50;

    /** 큐에서 꺼낸 직후(실행 수에 잡히기 전)의 틈을 비었다고 보지 않도록 연속으로 확인한다. */
    private static final int REQUIRED_IDLE_CHECKS = 2;

    private final BeanFactory beanFactory;
    private final Duration timeout;
    private volatile boolean running;

    @Autowired
    public UrgentNotificationDrainLifecycle(BeanFactory beanFactory) {
        this(beanFactory, Duration.ofSeconds(DRAIN_TIMEOUT_SECONDS));
    }

    UrgentNotificationDrainLifecycle(BeanFactory beanFactory, Duration timeout) {
        this.beanFactory = beanFactory;
        this.timeout = timeout;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        drain();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return ShutdownPhases.NOTIFICATION_DRAIN;
    }

    /** 컨텍스트 일시정지(테스트 컨텍스트 캐시 등)에서는 비울 이유가 없다 - 실제 종료 때만 돈다. */
    @Override
    public boolean isPauseable() {
        return false;
    }

    private void drain() {
        Map<String, ThreadPoolExecutor> pools = resolvePools();
        long started = System.nanoTime();
        long deadline = started + timeout.toNanos();
        int idleChecks = 0;
        while (true) {
            idleChecks = pools.values().stream().allMatch(UrgentNotificationDrainLifecycle::isIdle) ? idleChecks + 1 : 0;
            if (idleChecks >= REQUIRED_IDLE_CHECKS || System.nanoTime() - deadline >= 0) {
                break;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        long waitedMillis = (System.nanoTime() - started) / 1_000_000;
        if (idleChecks >= REQUIRED_IDLE_CHECKS) {
            log.info("[SHUTDOWN-DRAIN] 알림 풀 비움: waitedMs={}, 남은 작업={}", waitedMillis, remaining(pools));
        } else {
            // 남은 작업은 빈 파괴 때의 풀별 잔여 대기로 넘어간다. 그때는 Redis가 멈춰 쿨다운·문자 상한이 fail-open이다.
            log.warn("[SHUTDOWN-DRAIN] 알림 풀을 마감 안에 비우지 못함: waitedMs={}, limitMs={}, 남은 작업={}",
                    waitedMillis, timeout.toMillis(), remaining(pools));
        }
    }

    private Map<String, ThreadPoolExecutor> resolvePools() {
        Map<String, ThreadPoolExecutor> pools = new LinkedHashMap<>();
        for (String name : EXECUTOR_BEAN_NAMES) {
            try {
                pools.put(name, beanFactory.getBean(name, ThreadPoolTaskExecutor.class).getThreadPoolExecutor());
            } catch (RuntimeException e) {
                log.warn("[SHUTDOWN-DRAIN] 풀을 찾지 못해 건너뜀: executor={}, exception={}",
                        name, e.getClass().getSimpleName());
            }
        }
        return pools;
    }

    private static boolean isIdle(ThreadPoolExecutor pool) {
        return pool.getActiveCount() == 0 && pool.getQueue().isEmpty();
    }

    private static String remaining(Map<String, ThreadPoolExecutor> pools) {
        StringBuilder text = new StringBuilder();
        pools.forEach((name, pool) -> text.append(text.isEmpty() ? "" : ", ").append(name)
                .append("(active=").append(pool.getActiveCount())
                .append(", queue=").append(pool.getQueue().size()).append(')'));
        return text.toString();
    }
}

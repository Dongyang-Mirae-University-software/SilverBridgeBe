package kr.silverbridge.main.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.anomaly.client.AiLiveStreamSubscriber;
import kr.silverbridge.main.domain.anomaly.client.AnomalySignalParser;
import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.service.AnomalyDetectionService;
import kr.silverbridge.main.domain.anomaly.service.CameraLinkLostNotifier;
import kr.silverbridge.main.domain.anomaly.service.LiveAnalysisBroadcaster;
import kr.silverbridge.main.domain.camera.service.CameraService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 우아한 종료 순서 고정(2026-11-22 최종 점검 I-1·I-2):
 * <b>웹 서버 정지 → AI 화재 수신 중단 → 긴급·일반 알림 풀 비우기 → 0단계(Redis·WebSocket·스케줄러) → 빈 파괴</b>.
 *
 * <p>순서가 어긋나면 생기는 일: 수신 중단이 비우기보다 늦으면 풀을 비운 뒤 들어온 화재 신호가 이력·쿨다운만 남기고
 * 알림이 폐기된다. 비우기가 0단계보다 늦으면 Redis 없이 발송해 쿨다운·문자 상한이 fail-open(중복 발송)이 된다.</p>
 *
 * <p>앱 전체 컨텍스트는 DB·Redis가 있어야 떠서(Docker) 여기서는 {@link AsyncConfig}와 두 lifecycle 빈만 올리고,
 * 웹 서버·0단계 자리는 같은 단계 값을 가진 대역으로 채운다. 대기 시간 합은 {@link ShutdownGraceBudgetTest}가 본다.</p>
 */
class ShutdownOrderTest {

    /** Redis(LettuceConnectionFactory)·WebSocket 브로커·그 스케줄러가 멈추는 단계. */
    private static final int PHASE_ZERO = 0;

    @Test
    @DisplayName("단계 값: 웹 우아한 종료 > 웹 정지 > AI 수신 중단 > 알림 풀 비우기 > AsyncConfig 풀 > 0단계(Redis)")
    void 단계_값의_순서() {
        int asyncPools = new ThreadPoolTaskExecutor().getPhase();

        assertThat(WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE)
                .isGreaterThan(WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE);
        assertThat(WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE)
                .as("진행 중 HTTP 요청(SOS)이 끝난 뒤에 수신을 멈춘다").isGreaterThan(ShutdownPhases.AI_STREAM_SUBSCRIBER);
        assertThat(ShutdownPhases.AI_STREAM_SUBSCRIBER)
                .as("수신을 멈춘 뒤에 알림 풀을 비운다").isGreaterThan(ShutdownPhases.NOTIFICATION_DRAIN);
        assertThat(ShutdownPhases.NOTIFICATION_DRAIN).isGreaterThan(asyncPools);
        assertThat(asyncPools).isGreaterThan(PHASE_ZERO);
        assertThat(new LettuceConnectionFactory().getPhase())
                .as("Redis는 0단계에서 멈춘다 - 비우기는 그보다 먼저여야 한다").isEqualTo(PHASE_ZERO);
    }

    @Test
    @DisplayName("두 빈은 정해 둔 단계에서 멈추고, 자동 시작되며, 컨텍스트 일시정지 대상이 아니다")
    void 수신기와_비우기_빈의_lifecycle_설정() {
        AiLiveStreamSubscriber subscriber = subscriber(() -> { });
        UrgentNotificationDrainLifecycle drain = new UrgentNotificationDrainLifecycle(new DefaultListableBeanFactory());

        assertThat(subscriber.getPhase()).isEqualTo(ShutdownPhases.AI_STREAM_SUBSCRIBER);
        assertThat(drain.getPhase()).isEqualTo(ShutdownPhases.NOTIFICATION_DRAIN);
        assertThat(subscriber.isAutoStartup()).isTrue();
        assertThat(drain.isAutoStartup()).isTrue();
        // 일시정지 때 stop이 불리면 수신기는 다시 붙지 않는다(종료 전용 stop) - 일시정지에서 빼 둔다.
        assertThat(subscriber.isPauseable()).isFalse();
        assertThat(drain.isPauseable()).isFalse();
    }

    @Test
    @DisplayName("컨텍스트 종료: 웹 우아한 종료 → 웹 정지 → AI 수신 중단 → 알림 풀 비우기 → 0단계 순서로 멈춘다")
    void 종료_순서() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(AsyncConfig.class);
        // 등록 순서는 일부러 종료 순서와 거꾸로 둔다 - 순서를 정하는 것이 등록 순서가 아니라 단계 값임을 본다.
        context.registerBean("phaseZero", Recorder.class, () -> new Recorder(PHASE_ZERO, () -> order.add("0단계")));
        context.registerBean("urgentNotificationDrainLifecycle", UrgentNotificationDrainLifecycle.class,
                () -> new UrgentNotificationDrainLifecycle(context.getBeanFactory()) {
                    @Override
                    public void stop() {
                        order.add("알림 풀 비우기 시작");
                        super.stop();
                        order.add("알림 풀 비우기 끝");
                    }
                });
        context.registerBean("aiLiveStreamSubscriber", AiLiveStreamSubscriber.class,
                () -> subscriber(() -> order.add("AI 수신 중단")));
        context.registerBean("webStartStop", Recorder.class, () -> new Recorder(
                WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE, () -> order.add("웹 정지")));
        context.registerBean("webGraceful", Recorder.class, () -> new Recorder(
                WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE, () -> order.add("웹 우아한 종료")));
        context.refresh();

        context.close();

        // 수신기는 빈 파괴 때(@PreDestroy) 한 번 더 불린다 - 처음 불린 순서만 본다.
        assertThat(order.stream().distinct().toList()).containsExactly(
                "웹 우아한 종료", "웹 정지", "AI 수신 중단", "알림 풀 비우기 시작", "알림 풀 비우기 끝", "0단계");
    }

    @Test
    @DisplayName("알림 풀 비우기는 0단계(Redis가 멈추는 단계) 전에 긴급 작업을 끝내고, 그 뒤에 들어온 작업도 빈 파괴 전까지 받는다")
    void 비우기는_0단계_전에_긴급_작업을_끝낸다() throws Exception {
        CountDownLatch subscriberStopped = new CountDownLatch(1);
        AtomicBoolean urgentDone = new AtomicBoolean();
        AtomicReference<Boolean> doneWhenPhaseZeroStops = new AtomicReference<>();
        AtomicReference<ThreadPoolTaskExecutor> urgentPool = new AtomicReference<>();
        CountDownLatch lateRan = new CountDownLatch(1);

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(AsyncConfig.class);
        context.registerBean("aiLiveStreamSubscriber", AiLiveStreamSubscriber.class,
                () -> subscriber(subscriberStopped::countDown));
        context.registerBean("urgentNotificationDrainLifecycle", UrgentNotificationDrainLifecycle.class,
                () -> new UrgentNotificationDrainLifecycle(context.getBeanFactory(), Duration.ofSeconds(5)));
        context.registerBean("phaseZero", Recorder.class, () -> new Recorder(PHASE_ZERO, () -> {
            doneWhenPhaseZeroStops.set(urgentDone.get());
            urgentPool.get().execute(lateRan::countDown);
        }));
        context.refresh();
        urgentPool.set(context.getBean("urgentNotificationExecutor", ThreadPoolTaskExecutor.class));

        CountDownLatch started = new CountDownLatch(1);
        urgentPool.get().execute(() -> {
            started.countDown();
            await(subscriberStopped);   // 수신 중단 뒤에야 끝날 수 있는 작업 - 비우기가 수신 중단보다 먼저면 마감까지 묶인다
            sleep(200);
            urgentDone.set(true);
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        context.close();

        assertThat(doneWhenPhaseZeroStops.get()).as("0단계가 멈출 때 긴급 작업은 이미 끝나 있다").isTrue();
        assertThat(lateRan.await(5, TimeUnit.SECONDS)).as("비우기 뒤(0단계)에 들어온 긴급 작업도 실행된다").isTrue();
    }

    @Test
    @DisplayName("비우기 빈은 실행기 빈에 의존하지 않는다 - 의존하면 Spring이 실행기 단계에서 먼저 멈춰 순서가 설정과 달라진다")
    void 비우기_빈은_실행기에_의존하지_않는다() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(AsyncConfig.class, UrgentNotificationDrainLifecycle.class)) {
            String drain = context.getBeanNamesForType(UrgentNotificationDrainLifecycle.class)[0];

            for (String executor : UrgentNotificationDrainLifecycle.EXECUTOR_BEAN_NAMES) {
                assertThat(context.getBean(executor)).as(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
                assertThat(context.getBeanFactory().getDependentBeans(executor)).as(executor + "의 의존 빈").doesNotContain(drain);
            }
            assertThat(context.getBean(UrgentNotificationDrainLifecycle.class).isRunning())
                    .as("자동 시작돼 있어야 종료 단계에서 stop이 불린다").isTrue();
        }
    }

    /** 실제 수신기(의존 대상은 목). {@code stop()}이 불리면 먼저 {@code onStop}을 실행한다. */
    private static AiLiveStreamSubscriber subscriber(Runnable onStop) {
        return new AiLiveStreamSubscriber(new AnomalyProperties(), mock(AnomalySignalParser.class),
                mock(AnomalyDetectionService.class), mock(CameraService.class), new ObjectMapper(),
                mock(TaskScheduler.class), mock(LiveAnalysisBroadcaster.class), mock(CameraLinkLostNotifier.class)) {
            @Override
            public void stop() {
                onStop.run();
                super.stop();
            }
        };
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

    /** 정해진 단계에서 멈출 때 {@code onStop}을 실행하는 대역(웹 서버·0단계 자리). */
    private static final class Recorder implements SmartLifecycle {

        private final int phase;
        private final Runnable onStop;
        private volatile boolean running;

        private Recorder(int phase, Runnable onStop) {
            this.phase = phase;
            this.onStop = onStop;
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
            onStop.run();
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int getPhase() {
            return phase;
        }
    }
}

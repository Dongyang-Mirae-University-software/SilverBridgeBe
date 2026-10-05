package kr.silverbridge.main.domain.anomaly.listener;

import kr.silverbridge.main.domain.anomaly.client.AiClipClient;
import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyEvent;
import kr.silverbridge.main.domain.anomaly.event.AnomalyDetectedEvent;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyEventRepository;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipCaptureService;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipCooldown;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipService;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipStorage;
import kr.silverbridge.main.global.config.AsyncConfig;
import kr.silverbridge.main.global.enums.DetectedType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code clipExecutor} 포화 시 폐기와 클립 쿨다운의 관계(2026-10-05 QA 종합 점검 테스트 공백 보강).
 *
 * <p>클립 쿨다운은 executor 스레드 <b>안</b>({@link AnomalyClipCaptureService#capture})에서 잡는다. 그래서 포화로 폐기된
 * 작업은 쿨다운을 잡지도 않고, 다음 위험 감지(이력 쿨다운 1분 뒤)가 다시 시도한다 - "폐기되면 5분간 클립이 안 생긴다"는
 * 걱정은 성립하지 않는다. 누가 쿨다운 확인을 리스너 제출 전(AI 수신 스레드)으로 옮기면 폐기 건이 쿨다운만 남기게 되어
 * 여기서 깨진다.</p>
 */
@ExtendWith(MockitoExtension.class)
class AnomalyClipExecutorSaturationTest {

    private static final String SESSION = "ward_k3m9Q2aZ7pLx01Bc";
    private static final String KEY = "anomaly:clip:" + SESSION + ":FIRE";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private AnomalyEventRepository eventRepository;
    @Mock private AiClipClient aiClipClient;
    @Mock private AnomalyClipStorage storage;
    @Mock private AnomalyClipService clipService;

    private ThreadPoolTaskExecutor executor;
    private AnomalyClipListener listener;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        executor = (ThreadPoolTaskExecutor) new AsyncConfig().clipExecutor();
        AnomalyProperties properties = new AnomalyProperties();
        AnomalyClipCooldown cooldown = new AnomalyClipCooldown(redisTemplate, properties);
        listener = new AnomalyClipListener(new AnomalyClipCaptureService(
                properties, eventRepository, cooldown, aiClipClient, storage, clipService));
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        executor.shutdown();
    }

    private static AnomalyDetectedEvent event() {
        return new AnomalyDetectedEvent(11L, 37L, "WD0001", "김영희", SESSION, "거실", DetectedType.FIRE,
                OffsetDateTime.now());
    }

    /** 스레드 2개를 막고 큐 20칸을 채운다(clipExecutor = core 2 / max 2 / queue 20). */
    private void saturate() throws InterruptedException {
        CountDownLatch running = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            executor.execute(() -> {
                running.countDown();
                await(release);
            });
        }
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 20; i++) {
            executor.execute(() -> await(release));
        }
        assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();
    }

    @Test
    @DisplayName("포화면 폐기한다 - 호출 스레드(AI 수신)에서 실행하지도, 예외를 던지지도 않는다(CallerRuns 금지)")
    void rejectedWithoutCallerRuns() throws InterruptedException {
        saturate();
        AtomicBoolean ranOnCaller = new AtomicBoolean(false);
        Thread caller = Thread.currentThread();

        assertThatNoException().isThrownBy(() ->
                executor.execute(() -> ranOnCaller.set(Thread.currentThread() == caller)));

        assertThat(ranOnCaller).as("호출 스레드에서 돌면 AI 수신이 25초까지 묶인다").isFalse();
    }

    @Test
    @DisplayName("폐기된 클립 작업은 쿨다운을 잡지 않는다 - 풀린 뒤 다음 감지는 바로 쿨다운을 잡고 시도한다")
    void rejectedTaskLeavesNoCooldown() throws InterruptedException {
        saturate();

        executor.execute(() -> listener.handleAnomalyDetected(event()));
        verifyNoInteractions(redisTemplate, eventRepository, aiClipClient);

        // 포화가 풀린 뒤 같은 카메라·유형의 다음 위험 감지
        release.countDown();
        when(eventRepository.findById(11L)).thenReturn(Optional.of(AnomalyEvent.builder()
                .wardId("WD0001").sessionId(SESSION).detectedType(DetectedType.FIRE)
                .confidence(0.9).danger(true).incidentId(37L).build()));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(KEY), eq("1"), any(Duration.class))).thenReturn(true);
        when(storage.hasEnoughSpace()).thenReturn(false); // AI 호출 전에서 멈춘다(이 테스트의 관심은 쿨다운까지)

        executor.execute(() -> listener.handleAnomalyDetected(event()));

        verify(valueOps, timeout(5_000)).setIfAbsent(eq(KEY), eq("1"), any(Duration.class));
        verify(storage, timeout(5_000)).hasEnoughSpace();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

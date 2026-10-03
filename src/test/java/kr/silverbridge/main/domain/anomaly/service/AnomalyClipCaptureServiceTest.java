package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.client.AiClipClient;
import kr.silverbridge.main.domain.anomaly.client.AiClipClient.ClipMeta;
import kr.silverbridge.main.domain.anomaly.client.AiClipClient.ClipResult;
import kr.silverbridge.main.domain.anomaly.client.AiClipClient.Outcome;
import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyClip;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyClipStatus;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyEvent;
import kr.silverbridge.main.domain.anomaly.event.AnomalyDetectedEvent;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyEventRepository;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipService.StoredClip;
import kr.silverbridge.main.global.enums.DetectedType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 클립 생성 흐름 - 어떤 결과에서 쿨다운을 풀고(다음 감지에서 재시도) 어떤 결과에서 유지하는지, 실패 시 파일이 남지 않는지.
 */
@ExtendWith(MockitoExtension.class)
class AnomalyClipCaptureServiceTest {

    private static final String SESSION = "ward_a9cC5f_k3m";
    private static final byte[] WEBM = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};
    private static final OffsetDateTime DETECTED_AT = OffsetDateTime.parse("2026-10-04T10:00:00+09:00");

    @Mock private AnomalyEventRepository eventRepository;
    @Mock private AnomalyClipCooldown cooldown;
    @Mock private AiClipClient aiClipClient;
    @Mock private AnomalyClipStorage storage;
    @Mock private AnomalyClipService clipService;

    private AnomalyProperties properties;
    private AnomalyClipCaptureService service;

    @BeforeEach
    void setUp() {
        properties = new AnomalyProperties();
        service = new AnomalyClipCaptureService(properties, eventRepository, cooldown, aiClipClient, storage, clipService);
    }

    private static AnomalyDetectedEvent event() {
        return new AnomalyDetectedEvent(11L, 37L, "WD0001", "김영희", SESSION, "거실", DetectedType.FIRE, DETECTED_AT);
    }

    private void storedEvent(boolean danger) {
        when(eventRepository.findById(11L)).thenReturn(Optional.of(AnomalyEvent.builder()
                .wardId("WD0001").sessionId(SESSION).detectedType(DetectedType.FIRE)
                .confidence(0.9).danger(danger).incidentId(37L).build()));
    }

    private void readyToRequest() {
        storedEvent(true);
        when(cooldown.tryAcquire(SESSION, DetectedType.FIRE)).thenReturn(true);
        when(storage.hasEnoughSpace()).thenReturn(true);
    }

    private static ClipResult ok() {
        return new ClipResult(Outcome.OK, WEBM, new ClipMeta(5000, 25, 1920, 1080, null), null, 200);
    }

    private static ClipResult fail(Outcome outcome) {
        return new ClipResult(outcome, null, null, null, null);
    }

    @Test
    @DisplayName("정상 - AI 요청 → 파일 저장 → 행 기록, 쿨다운은 유지한다")
    void 정상_저장() {
        readyToRequest();
        when(aiClipClient.requestClip(SESSION, DETECTED_AT)).thenReturn(ok());
        when(storage.write(WEBM)).thenReturn("f.webm");
        AnomalyClip saved = mock(AnomalyClip.class);
        when(saved.getStatus()).thenReturn(AnomalyClipStatus.VISIBLE);
        when(clipService.record(any())).thenReturn(Optional.of(saved));

        service.capture(event());

        ArgumentCaptor<StoredClip> stored = ArgumentCaptor.forClass(StoredClip.class);
        verify(clipService).record(stored.capture());
        assertThat(stored.getValue().incidentId()).isEqualTo(37L);
        assertThat(stored.getValue().fileName()).isEqualTo("f.webm");
        assertThat(stored.getValue().detectedAt()).isEqualTo(DETECTED_AT);
        assertThat(stored.getValue().sizeBytes()).isEqualTo(WEBM.length);
        verify(cooldown, never()).release(anyString(), any());
        verify(storage, never()).delete(anyString());
    }

    @Test
    @DisplayName("킬 스위치가 꺼져 있으면 아무것도 하지 않는다")
    void 킬스위치() {
        properties.getClip().setEnabled(false);

        service.capture(event());

        verifyNoInteractions(eventRepository, cooldown, aiClipClient, storage, clipService);
    }

    @Test
    @DisplayName("danger=false 이력(CONFIDENCE 폴백)은 클립을 만들지 않는다")
    void 위험_아님() {
        storedEvent(false);

        service.capture(event());

        verifyNoInteractions(cooldown, aiClipClient);
    }

    @Test
    @DisplayName("클립 쿨다운 중이면 AI를 부르지 않는다")
    void 쿨다운_중() {
        storedEvent(true);
        when(cooldown.tryAcquire(SESSION, DetectedType.FIRE)).thenReturn(false);

        service.capture(event());

        verifyNoInteractions(aiClipClient);
        verify(cooldown, never()).release(anyString(), any());
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"NOT_ENOUGH_FRAMES", "SESSION_NOT_FOUND", "BUSY", "SERVER_ERROR",
            "INVALID_RESPONSE", "TOO_LARGE", "UNAVAILABLE"})
    @DisplayName("재시도 가능한 AI 실패면 쿨다운을 풀어 다음 감지에서 다시 시도한다")
    void 재시도_가능_실패(Outcome outcome) {
        readyToRequest();
        when(aiClipClient.requestClip(SESSION, DETECTED_AT)).thenReturn(fail(outcome));

        service.capture(event());

        verify(cooldown).release(SESSION, DetectedType.FIRE);
        verify(storage, never()).write(any());
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"REJECTED", "NOT_CONFIGURED"})
    @DisplayName("키·파라미터 결함(다시 보내도 같은 실패)이면 쿨다운을 유지해 AI를 두드리지 않는다")
    void 재시도_불가_실패(Outcome outcome) {
        readyToRequest();
        when(aiClipClient.requestClip(SESSION, DETECTED_AT)).thenReturn(fail(outcome));

        service.capture(event());

        verify(cooldown, never()).release(anyString(), any());
    }

    @Test
    @DisplayName("디스크 여유가 부족하면 AI를 부르지 않고 쿨다운을 유지한다")
    void 디스크_부족() {
        storedEvent(true);
        when(cooldown.tryAcquire(SESSION, DetectedType.FIRE)).thenReturn(true);
        when(storage.hasEnoughSpace()).thenReturn(false);

        service.capture(event());

        verifyNoInteractions(aiClipClient);
        verify(cooldown, never()).release(anyString(), any());
    }

    @Test
    @DisplayName("파일 쓰기에 실패하면 쿨다운을 푼다")
    void 쓰기_실패() {
        readyToRequest();
        when(aiClipClient.requestClip(SESSION, DETECTED_AT)).thenReturn(ok());
        when(storage.write(WEBM)).thenThrow(new UncheckedIOException(new IOException("disk")));

        service.capture(event());

        verify(cooldown).release(SESSION, DetectedType.FIRE);
        verifyNoInteractions(clipService);
    }

    @Test
    @DisplayName("행 기록이 예외로 실패하면 파일을 지우고 쿨다운을 푼다(예외는 밖으로 나가지 않는다)")
    void 기록_실패() {
        readyToRequest();
        when(aiClipClient.requestClip(SESSION, DETECTED_AT)).thenReturn(ok());
        when(storage.write(WEBM)).thenReturn("f.webm");
        when(clipService.record(any())).thenThrow(new IllegalStateException("db down"));

        service.capture(event());

        verify(storage).delete("f.webm");
        verify(cooldown).release(SESSION, DetectedType.FIRE);
    }

    @Test
    @DisplayName("상황·카메라가 사라졌거나 상한이면(기록 안 함) 파일을 지우고 쿨다운은 유지한다")
    void 기록_안함() {
        readyToRequest();
        when(aiClipClient.requestClip(SESSION, DETECTED_AT)).thenReturn(ok());
        when(storage.write(WEBM)).thenReturn("f.webm");
        when(clipService.record(any())).thenReturn(Optional.empty());

        service.capture(event());

        verify(storage).delete("f.webm");
        verify(cooldown, never()).release(anyString(), any());
    }

    @Test
    @DisplayName("감지 시각이 없으면(AI fallback) 요청에는 null, 기록에는 요청 시각을 쓴다")
    void 감지시각_없음() {
        readyToRequest();
        AnomalyDetectedEvent noTime = new AnomalyDetectedEvent(11L, 37L, "WD0001", "김영희", SESSION, "거실",
                DetectedType.FIRE, null);
        when(aiClipClient.requestClip(SESSION, null)).thenReturn(ok());
        when(storage.write(WEBM)).thenReturn("f.webm");
        when(clipService.record(any())).thenReturn(Optional.empty());
        OffsetDateTime before = OffsetDateTime.now();

        service.capture(noTime);

        ArgumentCaptor<StoredClip> stored = ArgumentCaptor.forClass(StoredClip.class);
        verify(clipService).record(stored.capture());
        assertThat(stored.getValue().detectedAt()).isAfterOrEqualTo(before);
    }
}

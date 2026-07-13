package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.dto.AnomalySignal;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyEvent;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyEventRepository;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.global.enums.DetectedType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AnomalyDetectionService 단위 테스트.
 *
 * 흐름(판정 → 쿨다운 → sessionId→wardId 매핑 → 적재)에서 각 관문이 실제로 이력을 막는지 검증한다.
 * 판정 자체의 규칙은 AnomalyJudgeTest 담당이라 여기서는 judge를 mock으로 두고 "관문" 동작만 본다.
 */
@ExtendWith(MockitoExtension.class)
class AnomalyDetectionServiceTest {

    private static final String SESSION_ID = "ward_a9cC5f_k3m";
    private static final String WARD_ID = "WD0001";

    @Mock private AnomalyJudge judge;
    @Mock private AnomalyEventCooldown cooldown;
    @Mock private CameraService cameraService;
    @Mock private AnomalyEventRepository anomalyEventRepository;

    @InjectMocks private AnomalyDetectionService detectionService;

    private AnomalySignal signal(OffsetDateTime analyzedAt) {
        return new AnomalySignal(SESSION_ID, DetectedType.FIRE, 0.84, true, analyzedAt);
    }

    @Test
    @DisplayName("이상감지로 판정되면 소유 피보호자를 매핑해 이력을 적재한다")
    void anomaly_savesHistory() {
        OffsetDateTime analyzedAt = OffsetDateTime.of(2026, 7, 13, 10, 20, 22, 0, ZoneOffset.UTC);
        AnomalySignal signal = signal(analyzedAt);
        when(judge.isAnomaly(signal)).thenReturn(true);
        when(cooldown.tryAcquire(SESSION_ID, DetectedType.FIRE)).thenReturn(true);
        when(cameraService.findWardIdBySessionId(SESSION_ID)).thenReturn(Optional.of(WARD_ID));
        when(anomalyEventRepository.save(any(AnomalyEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(detectionService.handle(signal)).isPresent();

        ArgumentCaptor<AnomalyEvent> captor = ArgumentCaptor.forClass(AnomalyEvent.class);
        verify(anomalyEventRepository).save(captor.capture());
        AnomalyEvent saved = captor.getValue();
        assertThat(saved.getWardId()).isEqualTo(WARD_ID);
        assertThat(saved.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(saved.getDetectedType()).isEqualTo(DetectedType.FIRE);
        assertThat(saved.getConfidence()).isEqualTo(0.84);
        assertThat(saved.isDanger()).isTrue();
        assertThat(saved.getDetectedAt()).isEqualTo(analyzedAt);
    }

    @Test
    @DisplayName("AI fallback 페이로드(analyzedAt 없음)도 적재하되 detectedAt은 null로 남긴다 (수신 시각과 섞지 않는다)")
    void missingAnalyzedAt_savedWithNullDetectedAt() {
        AnomalySignal signal = signal(null);
        when(judge.isAnomaly(signal)).thenReturn(true);
        when(cooldown.tryAcquire(SESSION_ID, DetectedType.FIRE)).thenReturn(true);
        when(cameraService.findWardIdBySessionId(SESSION_ID)).thenReturn(Optional.of(WARD_ID));
        when(anomalyEventRepository.save(any(AnomalyEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(detectionService.handle(signal)).isPresent();

        ArgumentCaptor<AnomalyEvent> captor = ArgumentCaptor.forClass(AnomalyEvent.class);
        verify(anomalyEventRepository).save(captor.capture());
        assertThat(captor.getValue().getDetectedAt()).isNull();
    }

    @Test
    @DisplayName("이상감지가 아니면 쿨다운·매핑도 타지 않고 이력도 남기지 않는다")
    void notAnomaly_skipped() {
        AnomalySignal signal = signal(OffsetDateTime.now());
        when(judge.isAnomaly(signal)).thenReturn(false);

        assertThat(detectionService.handle(signal)).isEmpty();

        verifyNoInteractions(cooldown, cameraService, anomalyEventRepository);
    }

    @Test
    @DisplayName("쿨다운 내 중복 신호는 이력을 남기지 않는다 (매 프레임 broadcast로 인한 이력 폭주 방지)")
    void withinCooldown_skipped() {
        AnomalySignal signal = signal(OffsetDateTime.now());
        when(judge.isAnomaly(signal)).thenReturn(true);
        when(cooldown.tryAcquire(SESSION_ID, DetectedType.FIRE)).thenReturn(false);

        assertThat(detectionService.handle(signal)).isEmpty();

        verifyNoInteractions(cameraService, anomalyEventRepository);
    }

    @Test
    @DisplayName("미등록 session_id는 소유자를 알 수 없으므로 이력을 남기지 않는다")
    void unknownSession_skipped() {
        AnomalySignal signal = signal(OffsetDateTime.now());
        when(judge.isAnomaly(signal)).thenReturn(true);
        when(cooldown.tryAcquire(SESSION_ID, DetectedType.FIRE)).thenReturn(true);
        when(cameraService.findWardIdBySessionId(SESSION_ID)).thenReturn(Optional.empty());

        assertThat(detectionService.handle(signal)).isEmpty();

        verifyNoInteractions(anomalyEventRepository);
    }
}

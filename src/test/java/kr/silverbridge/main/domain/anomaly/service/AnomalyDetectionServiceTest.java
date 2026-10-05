package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.dto.AnomalySignal;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyEvent;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.event.AnomalyDetectedEvent;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyEventRepository;
import kr.silverbridge.main.domain.camera.dto.CameraOwner;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.DetectedType;
import org.springframework.context.ApplicationEventPublisher;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
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

    private static final String CAMERA_LABEL = "거실";
    private static final Long INCIDENT_ID = 37L;

    @Mock private AnomalyJudge judge;
    @Mock private AnomalyIncidentService incidentService;
    @Mock private AnomalyEventCooldown cooldown;
    @Mock private CameraService cameraService;
    @Mock private AnomalyEventRepository anomalyEventRepository;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private AnomalyDetectionService detectionService;

    private AnomalySignal signal(OffsetDateTime analyzedAt) {
        return new AnomalySignal(SESSION_ID, DetectedType.FIRE, 0.84, true, analyzedAt);
    }

    /** 감지가 편입될 상황을 스터빙한다. id는 실제로는 DB가 채우므로 mock으로 대신한다. */
    private void givenIncident() {
        AnomalyIncident incident = mock(AnomalyIncident.class);
        when(incident.getId()).thenReturn(INCIDENT_ID);
        when(incidentService.resolveIncident(eq(WARD_ID), eq(SESSION_ID), eq(DetectedType.FIRE),
                any(OffsetDateTime.class), anyDouble())).thenReturn(incident);
    }

    /** 소유 카메라(거실) + 피보호자 이름 조회를 성공 경로로 스터빙한다. */
    private void givenRegisteredCamera() {
        when(cameraService.findOwnerBySessionId(SESSION_ID))
                .thenReturn(Optional.of(new CameraOwner(WARD_ID, CAMERA_LABEL)));
        User ward = mock(User.class);
        when(ward.getName()).thenReturn("김순자");
        when(userRepository.findById(WARD_ID)).thenReturn(Optional.of(ward));
    }

    @Test
    @DisplayName("이상감지로 판정되면 소유 피보호자를 매핑해 이력을 적재한다")
    void anomaly_savesHistory() {
        OffsetDateTime analyzedAt = OffsetDateTime.of(2026, 7, 13, 10, 20, 22, 0, ZoneOffset.UTC);
        AnomalySignal signal = signal(analyzedAt);
        when(judge.isAnomaly(signal)).thenReturn(true);
        when(cooldown.tryAcquire(SESSION_ID, DetectedType.FIRE)).thenReturn(true);
        givenRegisteredCamera();
        givenIncident();
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
        // 판정·통계의 단위는 상황이라 이력은 반드시 상황에 묶여 적재된다
        assertThat(saved.getIncidentId()).isEqualTo(INCIDENT_ID);
    }

    @Test
    @DisplayName("AI fallback 페이로드(analyzedAt 없음)도 적재하되 detectedAt은 null로 남긴다 (수신 시각과 섞지 않는다)")
    void missingAnalyzedAt_savedWithNullDetectedAt() {
        AnomalySignal signal = signal(null);
        when(judge.isAnomaly(signal)).thenReturn(true);
        when(cooldown.tryAcquire(SESSION_ID, DetectedType.FIRE)).thenReturn(true);
        givenRegisteredCamera();
        givenIncident();
        when(anomalyEventRepository.save(any(AnomalyEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(detectionService.handle(signal)).isPresent();

        ArgumentCaptor<AnomalyEvent> captor = ArgumentCaptor.forClass(AnomalyEvent.class);
        verify(anomalyEventRepository).save(captor.capture());
        assertThat(captor.getValue().getDetectedAt()).isNull();
    }

    @Test
    @DisplayName("이력이 적재되면 알림 이벤트를 발행한다 (문구에 필요한 이름·방 이름·분석 시각 포함)")
    void anomaly_publishesNotificationEvent() {
        OffsetDateTime analyzedAt = OffsetDateTime.now();
        AnomalySignal signal = signal(analyzedAt);
        when(judge.isAnomaly(signal)).thenReturn(true);
        when(cooldown.tryAcquire(SESSION_ID, DetectedType.FIRE)).thenReturn(true);
        givenRegisteredCamera();
        givenIncident();
        when(anomalyEventRepository.save(any(AnomalyEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        detectionService.handle(signal);

        ArgumentCaptor<AnomalyDetectedEvent> captor = ArgumentCaptor.forClass(AnomalyDetectedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        AnomalyDetectedEvent published = captor.getValue();
        assertThat(published.wardId()).isEqualTo(WARD_ID);
        assertThat(published.wardName()).isEqualTo("김순자");
        assertThat(published.sessionId()).isEqualTo(SESSION_ID);
        assertThat(published.cameraLabel()).isEqualTo(CAMERA_LABEL);
        assertThat(published.detectedType()).isEqualTo(DetectedType.FIRE);
        // 알림톡 승인 템플릿의 #{detectedAt} 원천 — 리스너가 KST로 포맷해 쓴다
        assertThat(published.detectedAt()).isEqualTo(analyzedAt);
        // 보호자가 알림에서 바로 오탐 응답을 하려면 판정 단위(상황) 식별자가 실려야 한다
        assertThat(published.incidentId()).isEqualTo(INCIDENT_ID);
    }

    @Test
    @DisplayName("이상감지가 아니면 쿨다운·매핑도 타지 않고 이력도 남기지 않는다")
    void notAnomaly_skipped() {
        AnomalySignal signal = signal(OffsetDateTime.now());
        when(judge.isAnomaly(signal)).thenReturn(false);

        assertThat(detectionService.handle(signal)).isEmpty();

        verifyNoInteractions(cooldown, cameraService, incidentService, anomalyEventRepository, eventPublisher);
    }

    @Test
    @DisplayName("쿨다운 내 중복 신호는 이력을 남기지 않는다 (매 프레임 broadcast로 인한 이력 폭주 방지)")
    void withinCooldown_skipped() {
        AnomalySignal signal = signal(OffsetDateTime.now());
        when(judge.isAnomaly(signal)).thenReturn(true);
        when(cooldown.tryAcquire(SESSION_ID, DetectedType.FIRE)).thenReturn(false);

        assertThat(detectionService.handle(signal)).isEmpty();

        // 이력이 없으면 알림도 없다 — 쿨다운이 이력·알림을 함께 억제한다(2단계 알림은 이력 적재 건에만 발행)
        verifyNoInteractions(cameraService, incidentService, anomalyEventRepository, eventPublisher);
    }

    @Test
    @DisplayName("미등록 session_id는 소유자를 알 수 없으므로 이력을 남기지 않는다")
    void unknownSession_skipped() {
        AnomalySignal signal = signal(OffsetDateTime.now());
        when(judge.isAnomaly(signal)).thenReturn(true);
        when(cooldown.tryAcquire(SESSION_ID, DetectedType.FIRE)).thenReturn(true);
        when(cameraService.findOwnerBySessionId(SESSION_ID)).thenReturn(Optional.empty());

        assertThat(detectionService.handle(signal)).isEmpty();

        // 소유자를 모르면 상황도 열지 않는다 — 주인 없는 판정 대상을 만들지 않는다
        verifyNoInteractions(incidentService, anomalyEventRepository, eventPublisher);
    }

    @Test
    @DisplayName("이력 저장에 실패하면 쿨다운을 되돌려 다음 프레임이 곧바로 다시 시도한다 (ANOM-G14)")
    void saveFailure_releasesCooldown() {
        AnomalySignal signal = signal(OffsetDateTime.now());
        when(judge.isAnomaly(signal)).thenReturn(true);
        when(cooldown.tryAcquire(SESSION_ID, DetectedType.FIRE)).thenReturn(true);
        when(cameraService.findOwnerBySessionId(SESSION_ID))
                .thenReturn(Optional.of(new CameraOwner(WARD_ID, CAMERA_LABEL)));
        givenIncident();
        when(anomalyEventRepository.save(any(AnomalyEvent.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("fk"));

        // 예외는 그대로 던진다 - AI 수신부(handleTextMessage)가 메시지 단위로 격리해 수신 스레드는 살아 있다
        assertThatThrownBy(() -> detectionService.handle(signal))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        verify(cooldown).release(SESSION_ID, DetectedType.FIRE);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("상황 편입·소유자 조회 중 예외도 쿨다운을 되돌린다 (ANOM-G14)")
    void lookupFailure_releasesCooldown() {
        AnomalySignal signal = signal(OffsetDateTime.now());
        when(judge.isAnomaly(signal)).thenReturn(true);
        when(cooldown.tryAcquire(SESSION_ID, DetectedType.FIRE)).thenReturn(true);
        when(cameraService.findOwnerBySessionId(SESSION_ID))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("db down"));

        assertThatThrownBy(() -> detectionService.handle(signal))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);

        verify(cooldown).release(SESSION_ID, DetectedType.FIRE);
    }

    @Test
    @DisplayName("이력 저장에 성공하면 쿨다운은 유지된다 - 같은 신호 중복 방지 (ANOM-G14)")
    void success_keepsCooldown() {
        AnomalySignal signal = signal(OffsetDateTime.now());
        when(judge.isAnomaly(signal)).thenReturn(true);
        when(cooldown.tryAcquire(SESSION_ID, DetectedType.FIRE)).thenReturn(true);
        givenRegisteredCamera();
        givenIncident();
        when(anomalyEventRepository.save(any(AnomalyEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(detectionService.handle(signal)).isPresent();

        verify(cooldown, never()).release(any(), any());
    }

    /** 쿨다운을 실제처럼 흉내 낸다 - 키가 있으면 거절, release로 지운다(Redis SET NX / DEL). */
    private java.util.Set<String> givenStatefulCooldown() {
        java.util.Set<String> keys = new java.util.HashSet<>();
        when(cooldown.tryAcquire(any(), any()))
                .thenAnswer(inv -> keys.add(inv.getArgument(0) + ":" + inv.getArgument(1)));
        doAnswer(inv -> keys.remove(inv.getArgument(0) + ":" + inv.getArgument(1)))
                .when(cooldown).release(any(), any());
        return keys;
    }

    @Test
    @DisplayName("저장 실패 → 쿨다운 해제 → 쿨다운 안의 다음 신호가 이력으로 저장된다 (ANOM-G14)")
    void saveFailure_thenNextSignalIsRecorded() {
        java.util.Set<String> keys = givenStatefulCooldown();
        AnomalySignal first = signal(OffsetDateTime.now());
        AnomalySignal next = signal(OffsetDateTime.now());
        when(judge.isAnomaly(any())).thenReturn(true);
        givenRegisteredCamera();
        givenIncident();
        when(anomalyEventRepository.save(any(AnomalyEvent.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> detectionService.handle(first))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(keys).as("실패한 신호는 쿨다운을 남기지 않는다").isEmpty();

        assertThat(detectionService.handle(next)).as("다음 신호는 쿨다운에 막히지 않고 저장된다").isPresent();
        verify(anomalyEventRepository, times(2)).save(any(AnomalyEvent.class));
        verify(eventPublisher, times(1)).publishEvent(any(AnomalyDetectedEvent.class));
        // 저장에 성공한 뒤에는 쿨다운이 확정돼 그 다음 프레임은 막힌다
        assertThat(keys).containsExactly(SESSION_ID + ":" + DetectedType.FIRE);
        assertThat(detectionService.handle(signal(OffsetDateTime.now()))).isEmpty();
    }

    @Test
    @DisplayName("save()는 성공했지만 커밋이 실패해 롤백되면 쿨다운을 해제한다 - 다음 신호가 저장된다 (ANOM-G14)")
    void commitFailure_releasesCooldownAfterRollback() {
        java.util.Set<String> keys = givenStatefulCooldown();
        AnomalySignal signal = signal(OffsetDateTime.now());
        when(judge.isAnomaly(any())).thenReturn(true);
        givenRegisteredCamera();
        givenIncident();
        when(anomalyEventRepository.save(any(AnomalyEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        // @Transactional 경계를 흉내 낸다 - 메서드는 정상 반환, 이후 커밋 단계(flush 제약 위반 등)에서 롤백
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(detectionService.handle(signal)).isPresent();
            assertThat(keys).as("커밋 전에는 쿨다운이 잡혀 있다").isNotEmpty();
            completeTransaction(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(keys).as("롤백되면 쿨다운을 해제한다").isEmpty();
        assertThat(detectionService.handle(signal(OffsetDateTime.now()))).isPresent();
    }

    @Test
    @DisplayName("커밋에 성공하면 쿨다운을 유지한다 - 커밋 후 동기화가 해제하지 않는다 (ANOM-G14)")
    void commitSuccess_keepsCooldown() {
        AnomalySignal signal = signal(OffsetDateTime.now());
        when(judge.isAnomaly(signal)).thenReturn(true);
        when(cooldown.tryAcquire(SESSION_ID, DetectedType.FIRE)).thenReturn(true);
        givenRegisteredCamera();
        givenIncident();
        when(anomalyEventRepository.save(any(AnomalyEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            detectionService.handle(signal);
            completeTransaction(org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }

        verify(cooldown, never()).release(any(), any());
    }

    @Test
    @DisplayName("커밋 결과를 알 수 없을 때(STATUS_UNKNOWN)도 해제한다 - 남겨 두면 1분간 화재 신호를 버린다 (ANOM-G14)")
    void unknownCompletion_releasesCooldown() {
        AnomalySignal signal = signal(OffsetDateTime.now());
        when(judge.isAnomaly(signal)).thenReturn(true);
        when(cooldown.tryAcquire(SESSION_ID, DetectedType.FIRE)).thenReturn(true);
        givenRegisteredCamera();
        givenIncident();
        when(anomalyEventRepository.save(any(AnomalyEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            detectionService.handle(signal);
            completeTransaction(org.springframework.transaction.support.TransactionSynchronization.STATUS_UNKNOWN);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }

        verify(cooldown).release(SESSION_ID, DetectedType.FIRE);
    }

    /** 트랜잭션 매니저가 커밋/롤백 뒤에 하는 일을 대신한다. */
    private static void completeTransaction(int status) {
        for (org.springframework.transaction.support.TransactionSynchronization sync
                : org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(status);
        }
    }

    @Test
    @DisplayName("미등록 세션은 쿨다운을 되돌리지 않는다 - 삭제된 카메라의 매 프레임 WARN 폭주 억제")
    void unknownSession_keepsCooldown() {
        AnomalySignal signal = signal(OffsetDateTime.now());
        when(judge.isAnomaly(signal)).thenReturn(true);
        when(cooldown.tryAcquire(SESSION_ID, DetectedType.FIRE)).thenReturn(true);
        when(cameraService.findOwnerBySessionId(SESSION_ID)).thenReturn(Optional.empty());

        assertThat(detectionService.handle(signal)).isEmpty();

        verify(cooldown, never()).release(any(), any());
    }
}

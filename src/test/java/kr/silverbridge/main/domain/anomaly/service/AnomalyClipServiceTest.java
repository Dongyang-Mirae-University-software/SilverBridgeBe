package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.client.AiClipClient.ClipMeta;
import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyClip;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyClipStatus;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyClipRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipService.ClipSummary;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipService.StoredClip;
import kr.silverbridge.main.domain.camera.dto.CameraOwner;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.global.enums.DetectedType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 클립 메타 - 기록 조건(상황·카메라·상한), 판정에 따른 비공개·복구, 이력 요약. */
@ExtendWith(MockitoExtension.class)
class AnomalyClipServiceTest {

    private static final String WARD = "WD0001";
    private static final String SESSION = "s1";
    private static final Long INCIDENT_ID = 37L;

    @Mock private AnomalyClipRepository clipRepository;
    @Mock private AnomalyIncidentRepository incidentRepository;
    @Mock private CameraService cameraService;

    private AnomalyProperties properties;
    private AnomalyClipService service;

    @BeforeEach
    void setUp() {
        properties = new AnomalyProperties();
        service = new AnomalyClipService(clipRepository, incidentRepository, cameraService, properties);
    }

    private static AnomalyIncident incident(AnomalyReviewStatus status) {
        AnomalyIncident incident = AnomalyIncident.builder().wardId(WARD).sessionId(SESSION)
                .detectedType(DetectedType.FIRE).detectedAt(OffsetDateTime.now()).confidence(0.9).build();
        incident.applyReviewStatus(status);
        return incident;
    }

    private static StoredClip stored() {
        return new StoredClip(INCIDENT_ID, WARD, SESSION, "f.webm", 1900, new ClipMeta(5000, 25, 1920, 1080, null),
                OffsetDateTime.now());
    }

    private void wardAlive() {
        when(clipRepository.lockWardForKeyShare(WARD)).thenReturn(Optional.of(WARD));
    }

    private void cameraAlive() {
        when(cameraService.findOwnerBySessionId(SESSION)).thenReturn(Optional.of(new CameraOwner(WARD, "거실")));
    }

    @Test
    @DisplayName("기록은 피보호자 → 상황 행 순서로 잠그고(탈퇴 purge와 같은 순서, L-1) 공개 상태로 남긴다")
    void 기록_공개() {
        wardAlive();
        when(incidentRepository.findByIdForUpdate(INCIDENT_ID)).thenReturn(Optional.of(incident(AnomalyReviewStatus.PENDING)));
        cameraAlive();
        when(clipRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<AnomalyClip> clip = service.record(stored());

        assertThat(clip).get().satisfies(saved -> {
            assertThat(saved.getStatus()).isEqualTo(AnomalyClipStatus.VISIBLE);
            assertThat(saved.getHiddenAt()).isNull();
            assertThat(saved.getDurationMs()).isEqualTo(5000);
        });
        InOrder order = inOrder(clipRepository, incidentRepository);
        order.verify(clipRepository).lockWardForKeyShare(WARD);
        order.verify(incidentRepository).findByIdForUpdate(INCIDENT_ID);
    }

    @Test
    @DisplayName("이미 오탐으로 확정된 상황이어도 판정 뒤 저장된 클립은 공개로 남긴다(L-2 - 진짜 화재 영상 보존)")
    void 기록_오탐상황도_공개() {
        wardAlive();
        when(incidentRepository.findByIdForUpdate(INCIDENT_ID))
                .thenReturn(Optional.of(incident(AnomalyReviewStatus.FALSE_ALARM)));
        cameraAlive();
        when(clipRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.record(stored())).get().satisfies(saved -> {
            assertThat(saved.getStatus()).isEqualTo(AnomalyClipStatus.VISIBLE);
            assertThat(saved.getHiddenAt()).isNull();
        });
    }

    @Test
    @DisplayName("피보호자가 이미 탈퇴했으면 상황 행을 잠그지 않고 기록하지 않는다")
    void 기록_피보호자없음() {
        when(clipRepository.lockWardForKeyShare(WARD)).thenReturn(Optional.empty());

        assertThat(service.record(stored())).isEmpty();
        verify(incidentRepository, never()).findByIdForUpdate(anyLong());
        verify(clipRepository, never()).save(any());
    }

    @Test
    @DisplayName("상황이 사라졌으면(탈퇴 CASCADE) 기록하지 않는다")
    void 기록_상황없음() {
        wardAlive();
        when(incidentRepository.findByIdForUpdate(INCIDENT_ID)).thenReturn(Optional.empty());

        assertThat(service.record(stored())).isEmpty();
        verify(clipRepository, never()).save(any());
    }

    @Test
    @DisplayName("요청 사이에 카메라가 삭제됐거나 다른 회원 것이 됐으면 기록하지 않는다")
    void 기록_카메라없음() {
        wardAlive();
        when(incidentRepository.findByIdForUpdate(INCIDENT_ID)).thenReturn(Optional.of(incident(AnomalyReviewStatus.PENDING)));
        when(cameraService.findOwnerBySessionId(SESSION)).thenReturn(Optional.of(new CameraOwner("WD0009", "거실")));

        assertThat(service.record(stored())).isEmpty();
        verify(clipRepository, never()).save(any());
    }

    @Test
    @DisplayName("상황당 상한에 닿으면 기록하지 않는다(비공개 클립도 센다)")
    void 기록_상한() {
        wardAlive();
        when(incidentRepository.findByIdForUpdate(INCIDENT_ID)).thenReturn(Optional.of(incident(AnomalyReviewStatus.PENDING)));
        cameraAlive();
        when(clipRepository.countByIncidentId(INCIDENT_ID)).thenReturn(12L);

        assertThat(service.record(stored())).isEmpty();
        verify(clipRepository, never()).save(any());
    }

    @Test
    @DisplayName("오탐 확정 → 비공개, 복구는 하지 않는다")
    void 오탐_비공개() {
        service.applyReviewStatus(INCIDENT_ID, AnomalyReviewStatus.FALSE_ALARM);

        verify(clipRepository).hideByIncidentId(eq(INCIDENT_ID), any());
        verify(clipRepository, never()).restoreByIncidentId(anyLong(), any());
    }

    @ParameterizedTest
    @EnumSource(value = AnomalyReviewStatus.class, names = {"REAL", "CONFLICTED", "PENDING"})
    @DisplayName("오탐이 아닌 판정(번복 포함)은 비공개였던 클립을 복구하고, 숨기지 않는다 - 동수·대기는 보존")
    void 번복_복구(AnomalyReviewStatus status) {
        service.applyReviewStatus(INCIDENT_ID, status);

        verify(clipRepository).restoreByIncidentId(eq(INCIDENT_ID), any());
        verify(clipRepository, never()).hideByIncidentId(anyLong(), any());
    }

    @Test
    @DisplayName("요약 - 상황별 최신 공개 클립 1건과 건수, 보관 기간이 지난 클립은 빼고 공개 클립 없는 상황은 항목 없음")
    void 요약() {
        AnomalyClip newest = clip(1L, 37L, OffsetDateTime.now().minusMinutes(1));
        AnomalyClip older = clip(2L, 37L, OffsetDateTime.now().minusMinutes(6));
        AnomalyClip expired = clip(3L, 38L, OffsetDateTime.now().minusDays(31));
        when(clipRepository.findByIncidentIdInAndStatusOrderByCreatedAtDesc(List.of(37L, 38L, 39L),
                AnomalyClipStatus.VISIBLE)).thenReturn(List.of(newest, older, expired));

        Map<Long, ClipSummary> summaries = service.summarize(List.of(37L, 38L, 39L));

        assertThat(summaries).containsOnlyKeys(37L);
        assertThat(summaries.get(37L).latest().getId()).isEqualTo(1L);
        assertThat(summaries.get(37L).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("보관 기간은 30일을 넘길 수 없다")
    void 보관기간_상한() {
        properties.getClip().setRetentionDays(365);

        assertThat(properties.getClip().effectiveRetentionDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("카메라 삭제 정리는 그 세션 클립 행을 지우고 파일 이름을 돌려준다")
    void 카메라_정리() {
        AnomalyClip target = clip(5L, 37L, OffsetDateTime.now());
        when(clipRepository.findByWardIdAndSessionIdIn(WARD, List.of(SESSION))).thenReturn(List.of(target));

        List<String> files = service.deleteAllOfCameras(WARD, List.of(SESSION));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(clipRepository).deleteAllByIdIn(ids.capture());
        assertThat(ids.getValue()).containsExactly(5L);
        assertThat(files).containsExactly(target.getFileName());
    }

    private static AnomalyClip clip(Long id, Long incidentId, OffsetDateTime createdAt) {
        AnomalyClip clip = AnomalyClip.builder().incidentId(incidentId).wardId(WARD).sessionId(SESSION)
                .fileName("file-" + id).sizeBytes(10).detectedAt(createdAt).status(AnomalyClipStatus.VISIBLE).build();
        ReflectionTestUtils.setField(clip, "id", id);
        ReflectionTestUtils.setField(clip, "createdAt", createdAt);
        return clip;
    }
}

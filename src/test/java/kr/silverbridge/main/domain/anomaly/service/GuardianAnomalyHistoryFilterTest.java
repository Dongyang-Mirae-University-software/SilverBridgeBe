package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.dto.AnomalyCameraFilter;
import kr.silverbridge.main.domain.anomaly.dto.AnomalyTypeFilter;
import kr.silverbridge.main.domain.anomaly.dto.GuardianAnomalyHistorySummary;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentFeedbackRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyReviewConflictLogRepository;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.DetectedType;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 보호자 이력의 유형·카메라 필터와 건수 요약(2026-10-07 / 카메라 2026-10-24). 인가가 필터·집계보다 먼저임을 고정한다. */
@ExtendWith(MockitoExtension.class)
class GuardianAnomalyHistoryFilterTest {

    private static final String GUARDIAN_ID = "GD0001";
    private static final String WARD_ID = "WD0001";

    @Mock private AnomalyIncidentRepository incidentRepository;
    @Mock private AnomalyIncidentFeedbackRepository feedbackRepository;
    @Mock private AnomalyReviewConflictLogRepository conflictLogRepository;
    @Mock private ConnectionService connectionService;
    @Mock private CameraService cameraService;
    @Mock private UserRepository userRepository;
    @Mock private AnomalyClipService clipService;

    private GuardianAnomalyService service;

    @BeforeEach
    void setUp() {
        service = new GuardianAnomalyService(incidentRepository, feedbackRepository, conflictLogRepository,
                connectionService, cameraService, userRepository, clipService);
    }

    private static AnomalyIncidentRepository.GuardianTypeStatusCount row(
            DetectedType type, AnomalyReviewStatus status, long total) {
        AnomalyIncidentRepository.GuardianTypeStatusCount row =
                mock(AnomalyIncidentRepository.GuardianTypeStatusCount.class);
        when(row.getDetectedType()).thenReturn(type);
        when(row.getReviewStatus()).thenReturn(status);
        when(row.getTotal()).thenReturn(total);
        return row;
    }

    private record StatusRow(DetectedType detectedType, AnomalyReviewStatus reviewStatus, long total)
            implements AnomalyIncidentRepository.GuardianTypeStatusCount {
        @Override public DetectedType getDetectedType() { return detectedType; }
        @Override public AnomalyReviewStatus getReviewStatus() { return reviewStatus; }
        @Override public long getTotal() { return total; }
    }

    @Test
    @DisplayName("type을 지정하면 그 유형이 쿼리로 넘어간다")
    void typeIsPassedToQuery() {
        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of(WARD_ID));
        when(incidentRepository.findHistory(any(), any(), anyBoolean(), anyBoolean(), any(), any(Pageable.class))).thenReturn(Page.empty());

        service.getHistory(GUARDIAN_ID, null, AnomalyTypeFilter.FALL, null, 0, 20);

        verify(incidentRepository).findHistory(eq(List.of(WARD_ID)), eq(DetectedType.FALL), eq(false), eq(false), any(),
                any(Pageable.class));
    }

    @Test
    @DisplayName("type을 생략하면 null이 넘어가 전체 조회다")
    void nullTypeMeansAll() {
        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of(WARD_ID));
        when(incidentRepository.findHistory(any(), any(), anyBoolean(), anyBoolean(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.getHistory(GUARDIAN_ID, null, null, null, 0, 20);

        ArgumentCaptor<DetectedType> captor = ArgumentCaptor.forClass(DetectedType.class);
        verify(incidentRepository).findHistory(any(), captor.capture(), anyBoolean(), anyBoolean(), any(), any(Pageable.class));
        assertThat(captor.getValue()).isNull();
    }

    @Test
    @DisplayName("wardId + type 조합도 연결이 없으면 403이고 저장소를 조회하지 않는다")
    void unconnectedWardWithTypeIsForbidden() {
        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.getHistory(GUARDIAN_ID, WARD_ID, AnomalyTypeFilter.FIRE, null, 0, 20))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANOMALY_NOT_AUTHORIZED);
        verify(incidentRepository, never()).findHistory(any(), any(), anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("요약: 유형·판정별로 합산하고 세 유형을 항상 담는다")
    void summaryAggregates() {
        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of(WARD_ID));
        List<AnomalyIncidentRepository.GuardianTypeStatusCount> rows = List.of(
                row(DetectedType.FIRE, AnomalyReviewStatus.PENDING, 2),
                row(DetectedType.FIRE, AnomalyReviewStatus.REAL, 3),
                row(DetectedType.FALL, AnomalyReviewStatus.CONFLICTED, 1),
                row(DetectedType.FALL, AnomalyReviewStatus.FALSE_ALARM, 4));
        when(cameraService.findSessionIdsByWardIds(List.of(WARD_ID))).thenReturn(List.of("ward_live"));
        when(incidentRepository.countForGuardianSummary(List.of(WARD_ID), List.of("ward_live"))).thenReturn(rows);

        GuardianAnomalyHistorySummary summary = service.getHistorySummary(GUARDIAN_ID, null);

        assertThat(summary.total()).isEqualTo(10);
        assertThat(summary.pendingCount()).isEqualTo(2);
        assertThat(summary.conflictedCount()).isEqualTo(1);
        assertThat(summary.needsReviewCount()).isEqualTo(3);
        assertThat(summary.byType().fire()).isEqualTo(5);
        assertThat(summary.byType().fall()).isEqualTo(5);
        assertThat(summary.byType().weapon()).isZero();
        assertThat(summary.deletedCameraCount()).isZero();
    }

    @Test
    @DisplayName("camera=DELETED면 지금 등록된 카메라 세션을 넘기고 '없는 세션만'으로 거른다")
    void deletedCameraFilterPassesLiveSessions() {
        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of(WARD_ID));
        when(cameraService.findSessionIdsByWardIds(List.of(WARD_ID))).thenReturn(List.of("ward_live"));
        when(incidentRepository.findHistory(any(), any(), anyBoolean(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.getHistory(GUARDIAN_ID, null, AnomalyTypeFilter.FIRE, AnomalyCameraFilter.DELETED, 0, 20);

        verify(incidentRepository).findHistory(eq(List.of(WARD_ID)), eq(DetectedType.FIRE), eq(true), eq(false),
                eq(List.of("ward_live")), any(Pageable.class));
    }

    @Test
    @DisplayName("camera=EXISTING이면 '있는 세션만', 등록된 카메라가 없으면 매칭 불가 값 하나를 넘긴다(빈 IN 절 회피)")
    void existingCameraFilterWithoutCamerasUsesNoMatch() {
        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of(WARD_ID));
        when(cameraService.findSessionIdsByWardIds(List.of(WARD_ID))).thenReturn(List.of());
        when(incidentRepository.findHistory(any(), any(), anyBoolean(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.getHistory(GUARDIAN_ID, null, null, AnomalyCameraFilter.EXISTING, 0, 20);

        verify(incidentRepository).findHistory(eq(List.of(WARD_ID)), eq(null), eq(false), eq(true),
                eq(List.of("")), any(Pageable.class));
    }

    @Test
    @DisplayName("camera를 생략하면 카메라 목록을 조회하지 않는다")
    void noCameraFilterSkipsCameraLookup() {
        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of(WARD_ID));
        when(incidentRepository.findHistory(any(), any(), anyBoolean(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.getHistory(GUARDIAN_ID, null, null, null, 0, 20);

        verify(cameraService, never()).findSessionIdsByWardIds(any());
    }

    @Test
    @DisplayName("camera 필터도 연결 없는 wardId면 403이고 카메라·이력을 조회하지 않는다")
    void deletedCameraFilterOnUnconnectedWardIsForbidden() {
        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.getHistory(GUARDIAN_ID, WARD_ID, null, AnomalyCameraFilter.DELETED, 0, 20))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANOMALY_NOT_AUTHORIZED);
        verify(cameraService, never()).findSessionIdsByWardIds(any());
        verify(incidentRepository, never()).findHistory(any(), any(), anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("요약: 기본 집계와 삭제된 카메라 건수가 같은 '지금 등록된 카메라' 목록을 기준으로 나뉜다(카메라 조회 1회)")
    void summaryCountsDeletedCamera() {
        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of(WARD_ID));
        when(cameraService.findSessionIdsByWardIds(List.of(WARD_ID))).thenReturn(List.of("ward_live"));
        List<AnomalyIncidentRepository.GuardianTypeStatusCount> rows =
                List.of(row(DetectedType.FIRE, AnomalyReviewStatus.PENDING, 2));
        when(incidentRepository.countForGuardianSummary(List.of(WARD_ID), List.of("ward_live"))).thenReturn(rows);
        when(incidentRepository.countDeletedCameraForGuardian(List.of(WARD_ID), List.of("ward_live"))).thenReturn(4L);

        GuardianAnomalyHistorySummary summary = service.getHistorySummary(GUARDIAN_ID, null);

        assertThat(summary.total()).as("등록된 카메라 상황만").isEqualTo(2);
        assertThat(summary.needsReviewCount()).isEqualTo(2);
        assertThat(summary.deletedCameraCount()).as("삭제된 카메라 상황은 따로").isEqualTo(4);
        verify(cameraService).findSessionIdsByWardIds(List.of(WARD_ID));
    }

    @Test
    @DisplayName("요약: 등록된 카메라가 없으면 매칭 불가 값 하나로 집계해 기본 건수는 0이 된다(빈 IN 절 회피)")
    void summaryWithoutCamerasUsesNoMatch() {
        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of(WARD_ID));
        when(cameraService.findSessionIdsByWardIds(List.of(WARD_ID))).thenReturn(List.of());
        when(incidentRepository.countForGuardianSummary(List.of(WARD_ID), List.of(""))).thenReturn(List.of());
        when(incidentRepository.countDeletedCameraForGuardian(List.of(WARD_ID), List.of(""))).thenReturn(3L);

        GuardianAnomalyHistorySummary summary = service.getHistorySummary(GUARDIAN_ID, null);

        assertThat(summary.total()).isZero();
        assertThat(summary.needsReviewCount()).isZero();
        assertThat(summary.deletedCameraCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("요약: total은 세 유형 밖의 행도 센다 - type 없는 이력 totalElements와 어긋나지 않게")
    void summaryTotalCountsEveryRow() {
        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of(WARD_ID));
        List<AnomalyIncidentRepository.GuardianTypeStatusCount> rows = List.of(
                row(DetectedType.FIRE, AnomalyReviewStatus.REAL, 2),
                row(DetectedType.UNKNOWN, AnomalyReviewStatus.PENDING, 1));
        when(incidentRepository.countForGuardianSummary(eq(List.of(WARD_ID)), any())).thenReturn(rows);

        GuardianAnomalyHistorySummary summary = service.getHistorySummary(GUARDIAN_ID, null);

        assertThat(summary.total()).isEqualTo(3);
        assertThat(summary.byType().fire()).isEqualTo(2);
    }

    @Test
    @DisplayName("대시보드 확인 필요 건수: 지금 등록된 카메라의 PENDING+CONFLICTED만 세고 삭제 건수는 조회하지 않는다")
    void needsReviewCountUsesRegisteredCameras() {
        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of(WARD_ID));
        when(cameraService.findSessionIdsByWardIds(List.of(WARD_ID))).thenReturn(List.of("ward_live"));
        // 유형은 읽지 않으므로 엄격한 목 대신 값 객체로 넘긴다
        List<AnomalyIncidentRepository.GuardianTypeStatusCount> rows = List.of(
                new StatusRow(DetectedType.FIRE, AnomalyReviewStatus.PENDING, 2),
                new StatusRow(DetectedType.FALL, AnomalyReviewStatus.CONFLICTED, 1),
                new StatusRow(DetectedType.FIRE, AnomalyReviewStatus.REAL, 5));
        when(incidentRepository.countForGuardianSummary(List.of(WARD_ID), List.of("ward_live"))).thenReturn(rows);

        assertThat(service.getNeedsReviewCount(GUARDIAN_ID, null)).isEqualTo(3);
        verify(incidentRepository, never()).countDeletedCameraForGuardian(any(), any());
    }

    @Test
    @DisplayName("대시보드 확인 필요 건수: 연결 없는 wardId는 403이고 집계하지 않는다 / 연결이 없으면 0")
    void needsReviewCountAuthorization() {
        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(false);
        assertThatThrownBy(() -> service.getNeedsReviewCount(GUARDIAN_ID, WARD_ID))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANOMALY_NOT_AUTHORIZED);

        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of());
        assertThat(service.getNeedsReviewCount(GUARDIAN_ID, null)).isZero();
        verify(incidentRepository, never()).countForGuardianSummary(any(), any());
    }

    @Test
    @DisplayName("요약: 연결 없는 wardId는 403이고 집계하지 않는다")
    void summaryUnconnectedWardIsForbidden() {
        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.getHistorySummary(GUARDIAN_ID, WARD_ID))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANOMALY_NOT_AUTHORIZED);
        verify(incidentRepository, never()).countForGuardianSummary(any(), any());
    }

    @Test
    @DisplayName("요약: 연결된 피보호자가 없으면 빈 요약이고 집계하지 않는다")
    void summaryWithoutConnectionIsEmpty() {
        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of());

        assertThat(service.getHistorySummary(GUARDIAN_ID, null)).isEqualTo(GuardianAnomalyHistorySummary.EMPTY);
        verify(incidentRepository, never()).countForGuardianSummary(any(), any());
    }

    @Test
    @DisplayName("offset이 int를 넘는 page → 마지막 가능 페이지로 보정(넘으면 500이었다, 2026-10-26)")
    void 큰페이지_보정() {
        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of(WARD_ID));
        when(incidentRepository.findHistory(any(), any(), anyBoolean(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.getHistory(GUARDIAN_ID, null, null, null, 100_000_000, 50);

        org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(incidentRepository).findHistory(any(), any(), anyBoolean(), anyBoolean(), any(), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(Integer.MAX_VALUE / 50);
        assertThat(captor.getValue().getOffset()).isLessThanOrEqualTo(Integer.MAX_VALUE);
    }
}

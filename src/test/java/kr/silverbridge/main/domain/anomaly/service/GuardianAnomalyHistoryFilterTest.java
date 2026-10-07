package kr.silverbridge.main.domain.anomaly.service;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 보호자 이력의 유형 필터와 건수 요약(2026-10-07). 인가가 필터·집계보다 먼저임을 고정한다. */
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

    @Test
    @DisplayName("type을 지정하면 그 유형이 쿼리로 넘어간다")
    void typeIsPassedToQuery() {
        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of(WARD_ID));
        when(incidentRepository.findHistory(any(), any(), any(Pageable.class))).thenReturn(Page.empty());

        service.getHistory(GUARDIAN_ID, null, AnomalyTypeFilter.FALL, 0, 20);

        verify(incidentRepository).findHistory(eq(List.of(WARD_ID)), eq(DetectedType.FALL), any(Pageable.class));
    }

    @Test
    @DisplayName("type을 생략하면 null이 넘어가 전체 조회다")
    void nullTypeMeansAll() {
        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of(WARD_ID));
        when(incidentRepository.findHistory(any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.getHistory(GUARDIAN_ID, null, null, 0, 20);

        ArgumentCaptor<DetectedType> captor = ArgumentCaptor.forClass(DetectedType.class);
        verify(incidentRepository).findHistory(any(), captor.capture(), any(Pageable.class));
        assertThat(captor.getValue()).isNull();
    }

    @Test
    @DisplayName("wardId + type 조합도 연결이 없으면 403이고 저장소를 조회하지 않는다")
    void unconnectedWardWithTypeIsForbidden() {
        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.getHistory(GUARDIAN_ID, WARD_ID, AnomalyTypeFilter.FIRE, 0, 20))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANOMALY_NOT_AUTHORIZED);
        verify(incidentRepository, never()).findHistory(any(), any(), any());
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
        when(incidentRepository.countForGuardianSummary(List.of(WARD_ID))).thenReturn(rows);

        GuardianAnomalyHistorySummary summary = service.getHistorySummary(GUARDIAN_ID, null);

        assertThat(summary.total()).isEqualTo(10);
        assertThat(summary.pendingCount()).isEqualTo(2);
        assertThat(summary.conflictedCount()).isEqualTo(1);
        assertThat(summary.byType().fire()).isEqualTo(5);
        assertThat(summary.byType().fall()).isEqualTo(5);
        assertThat(summary.byType().weapon()).isZero();
    }

    @Test
    @DisplayName("요약: 연결 없는 wardId는 403이고 집계하지 않는다")
    void summaryUnconnectedWardIsForbidden() {
        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.getHistorySummary(GUARDIAN_ID, WARD_ID))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANOMALY_NOT_AUTHORIZED);
        verify(incidentRepository, never()).countForGuardianSummary(any());
    }

    @Test
    @DisplayName("요약: 연결된 피보호자가 없으면 빈 요약이고 집계하지 않는다")
    void summaryWithoutConnectionIsEmpty() {
        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of());

        assertThat(service.getHistorySummary(GUARDIAN_ID, null)).isEqualTo(GuardianAnomalyHistorySummary.EMPTY);
        verify(incidentRepository, never()).countForGuardianSummary(any());
    }
}

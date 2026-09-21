package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalyIncidentItem;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncidentFeedback;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyVerdict;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentFeedbackRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.DetectedType;
import kr.silverbridge.main.global.response.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관리자 이상감지 로그(조회 전용) 검증.
 *
 * <p>2026-09-21 관리자 정정을 폐지했다 - 여기서는 조회가 전체를 보고 보호자 응답 내역을 함께 주는지만 고정한다.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminAnomalyServiceTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final String ADMIN_ID = "AD0001";
    private static final Long INCIDENT_ID = 37L;

    @Mock private AnomalyIncidentRepository incidentRepository;
    @Mock private AnomalyIncidentFeedbackRepository feedbackRepository;
    @Mock private CameraService cameraService;
    @Mock private UserRepository userRepository;

    private AdminAnomalyService service;

    @BeforeEach
    void setUp() {
        service = new AdminAnomalyService(
                incidentRepository, feedbackRepository, cameraService, userRepository);

        when(cameraService.findLabelsBySessionIds(any())).thenReturn(java.util.Map.of());
        when(userRepository.findAllById(any())).thenReturn(List.of());
        when(feedbackRepository.findByIncidentId(any())).thenReturn(List.of());
        when(feedbackRepository.findByIncidentIdIn(any())).thenReturn(List.of());
    }

    private AnomalyIncident incident(AnomalyReviewStatus status) {
        AnomalyIncident incident = AnomalyIncident.builder()
                .wardId("WD0001")
                .sessionId("ward_a9cC5f_k3m")
                .detectedType(DetectedType.FIRE)
                .detectedAt(OffsetDateTime.of(2026, 9, 2, 9, 0, 0, 0, KST))
                .confidence(0.87)
                .build();
        incident.applyReviewStatus(status);
        ReflectionTestUtils.setField(incident, "id", INCIDENT_ID);
        return incident;
    }

    private AnomalyIncidentFeedback feedback(String guardianId, AnomalyVerdict verdict) {
        return AnomalyIncidentFeedback.builder()
                .incidentId(INCIDENT_ID)
                .guardianId(guardianId)
                .verdict(verdict)
                .build();
    }

    @Nested
    @DisplayName("목록 조회")
    class Search {

        @Test
        @DisplayName("연결 여부로 좁히지 않는다 - 관리자는 전체를 본다")
        void 전체_조회() {
            AnomalyIncident target = incident(AnomalyReviewStatus.PENDING);
            when(incidentRepository.searchForAdmin(any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(target), PageRequest.of(0, 20), 1));

            PageResponse<AdminAnomalyIncidentItem> result = service.getIncidents(null, null, 0, 20);

            assertThat(result.content()).hasSize(1);
            assertThat(result.totalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("응답이 없는 상황은 빈 배열이다 - null이 아니다")
        void 응답_없으면_빈배열() {
            AnomalyIncident target = incident(AnomalyReviewStatus.PENDING);
            when(incidentRepository.searchForAdmin(any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(target), PageRequest.of(0, 20), 1));

            PageResponse<AdminAnomalyIncidentItem> result = service.getIncidents(null, null, 0, 20);

            assertThat(result.content().get(0).feedbacks()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("보호자 응답 내역이 함께 온다 - 관리자가 무엇을 보고 정정할지 판단할 근거")
        void 응답_내역_포함() {
            AnomalyIncident target = incident(AnomalyReviewStatus.CONFLICTED);
            when(incidentRepository.searchForAdmin(any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(target), PageRequest.of(0, 20), 1));
            when(feedbackRepository.findByIncidentIdIn(any())).thenReturn(List.of(
                    feedback("GD0001", AnomalyVerdict.REAL),
                    feedback("GD0002", AnomalyVerdict.FALSE_ALARM)));

            PageResponse<AdminAnomalyIncidentItem> result = service.getIncidents(
                    AnomalyReviewStatus.CONFLICTED, null, 0, 20);

            assertThat(result.content().get(0).feedbacks()).hasSize(2);
        }

        @Test
        @DisplayName("빈 페이지여도 전체 건수 같은 페이징 정보는 살아 있다")
        void 빈_페이지() {
            when(incidentRepository.searchForAdmin(any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(5, 20), 3));

            PageResponse<AdminAnomalyIncidentItem> result = service.getIncidents(null, null, 5, 20);

            assertThat(result.content()).isEmpty();
            assertThat(result.totalElements()).isEqualTo(3);
        }

        @Test
        @DisplayName("페이지 크기는 50을 넘지 않는다")
        void 페이지_크기_상한() {
            when(incidentRepository.searchForAdmin(any(), any(), any()))
                    .thenReturn(Page.empty(PageRequest.of(0, 50)));

            service.getIncidents(null, null, 0, 500);

            verify(incidentRepository).searchForAdmin(any(), any(),
                    eq(PageRequest.of(0, 50)));
        }
    }
}

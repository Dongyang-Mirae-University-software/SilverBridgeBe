package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.admin.service.AdminAuditLogService;
import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalyIncidentItem;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncidentFeedback;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyVerdict;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentFeedbackRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.AdminAuditAction;
import kr.silverbridge.main.global.enums.DetectedType;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관리자 이상감지 로그·정정 검증.
 *
 * <p>여기서 고정하는 것은 <b>정정이 무엇을 바꾸고 무엇을 바꾸지 않는가</b>다.
 * 상태만 바뀌고 보호자 응답은 남아야 하며, 정정한 뒤에는 뒤늦은 보호자 응답으로 뒤집히지 않아야 한다.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminAnomalyServiceTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final String ADMIN_ID = "AD0001";
    private static final Long INCIDENT_ID = 37L;

    @Mock private AnomalyIncidentRepository incidentRepository;
    @Mock private AnomalyIncidentFeedbackRepository feedbackRepository;
    @Mock private AdminAuditLogService auditLogService;
    @Mock private CameraService cameraService;
    @Mock private UserRepository userRepository;

    private AdminAnomalyService service;

    @BeforeEach
    void setUp() {
        service = new AdminAnomalyService(
                incidentRepository, feedbackRepository, auditLogService, cameraService, userRepository);

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
    @DisplayName("판정 정정")
    class Resolve {

        @Test
        @DisplayName("정정하면 상태가 바뀌고 정정자·시각이 기록된다")
        void 정정_반영() {
            AnomalyIncident target = incident(AnomalyReviewStatus.CONFLICTED);
            when(incidentRepository.findById(INCIDENT_ID)).thenReturn(Optional.of(target));

            AdminAnomalyIncidentItem result =
                    service.resolve(ADMIN_ID, INCIDENT_ID, AnomalyReviewStatus.FALSE_ALARM, "요리 연기");

            assertThat(result.reviewStatus()).isEqualTo(AnomalyReviewStatus.FALSE_ALARM);
            assertThat(result.resolvedBy()).isEqualTo(ADMIN_ID);
            assertThat(result.resolvedAt()).isNotNull();
            assertThat(result.reviewNote()).isEqualTo("요리 연기");
        }

        @Test
        @DisplayName("정정은 보호자 응답 원본을 지우지 않는다 - 무엇을 근거로 뒤집었는지 남아야 한다")
        void 응답_원본_보존() {
            AnomalyIncident target = incident(AnomalyReviewStatus.CONFLICTED);
            when(incidentRepository.findById(INCIDENT_ID)).thenReturn(Optional.of(target));
            when(feedbackRepository.findByIncidentId(INCIDENT_ID)).thenReturn(List.of(
                    feedback("GD0001", AnomalyVerdict.REAL),
                    feedback("GD0002", AnomalyVerdict.FALSE_ALARM)));

            AdminAnomalyIncidentItem result =
                    service.resolve(ADMIN_ID, INCIDENT_ID, AnomalyReviewStatus.FALSE_ALARM, null);

            assertThat(result.feedbacks()).hasSize(2);
            assertThat(result.feedbacks())
                    .extracting(f -> f.verdict().name())
                    .containsExactlyInAnyOrder("REAL", "FALSE_ALARM");
            verify(feedbackRepository, never()).deleteAll();
        }

        @Test
        @DisplayName("정정한 뒤에는 뒤늦은 보호자 응답으로 상태가 뒤집히지 않는다")
        void 정정_후_재계산_차단() {
            AnomalyIncident target = incident(AnomalyReviewStatus.CONFLICTED);
            when(incidentRepository.findById(INCIDENT_ID)).thenReturn(Optional.of(target));

            service.resolve(ADMIN_ID, INCIDENT_ID, AnomalyReviewStatus.FALSE_ALARM, null);

            // 보호자 응답 경로가 쓰는 재계산 메서드를 직접 불러도 확정 상태가 유지되어야 한다
            target.applyReviewStatus(AnomalyReviewStatus.REAL);

            assertThat(target.getReviewStatus()).isEqualTo(AnomalyReviewStatus.FALSE_ALARM);
            assertThat(target.isAdminResolved()).isTrue();
        }

        @Test
        @DisplayName("이미 정정한 건도 다시 정정할 수 있다 - 관리자도 잘못 누를 수 있다")
        void 재정정_허용() {
            AnomalyIncident target = incident(AnomalyReviewStatus.CONFLICTED);
            when(incidentRepository.findById(INCIDENT_ID)).thenReturn(Optional.of(target));

            service.resolve(ADMIN_ID, INCIDENT_ID, AnomalyReviewStatus.FALSE_ALARM, "1차");
            AdminAnomalyIncidentItem second =
                    service.resolve("AD0002", INCIDENT_ID, AnomalyReviewStatus.REAL, "2차 정정");

            assertThat(second.reviewStatus()).isEqualTo(AnomalyReviewStatus.REAL);
            assertThat(second.resolvedBy()).isEqualTo("AD0002");
            assertThat(second.reviewNote()).isEqualTo("2차 정정");
        }

        @Test
        @DisplayName("정정할 때마다 감사 로그를 남긴다 - 개인 이력을 뒤집는 조작이라 추적이 필요하다")
        void 감사로그_기록() {
            AnomalyIncident target = incident(AnomalyReviewStatus.CONFLICTED);
            when(incidentRepository.findById(INCIDENT_ID)).thenReturn(Optional.of(target));

            service.resolve(ADMIN_ID, INCIDENT_ID, AnomalyReviewStatus.FALSE_ALARM, "요리 연기");

            verify(auditLogService).log(
                    eq(ADMIN_ID),
                    eq(AdminAuditAction.ANOMALY_REVIEW_RESOLVE),
                    eq(String.valueOf(INCIDENT_ID)),
                    anyString());
        }

        @Test
        @DisplayName("PENDING·CONFLICTED로는 되돌릴 수 없다")
        void 되돌리기_거부() {
            AnomalyIncident target = incident(AnomalyReviewStatus.CONFLICTED);
            when(incidentRepository.findById(INCIDENT_ID)).thenReturn(Optional.of(target));

            assertThatThrownBy(() -> service.resolve(ADMIN_ID, INCIDENT_ID, AnomalyReviewStatus.PENDING, null))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANOMALY_INVALID_REVIEW_STATUS);

            assertThatThrownBy(() -> service.resolve(ADMIN_ID, INCIDENT_ID, AnomalyReviewStatus.CONFLICTED, null))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANOMALY_INVALID_REVIEW_STATUS);

            // 잘못된 값이면 상태를 건드리지도, 감사 로그를 남기지도 않는다
            assertThat(target.isAdminResolved()).isFalse();
            verify(auditLogService, never()).log(anyString(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("없는 상황을 정정하려 하면 404다")
        void 없는_상황() {
            when(incidentRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolve(ADMIN_ID, 999L, AnomalyReviewStatus.REAL, null))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANOMALY_INCIDENT_NOT_FOUND);
        }
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

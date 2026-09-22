package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalyIncidentItem;
import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalyPeriod;
import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalySummaryResponse;
import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalyTypeFilter;
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
import org.mockito.ArgumentCaptor;
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
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
            when(incidentRepository.searchForAdmin(any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(target), PageRequest.of(0, 20), 1));

            PageResponse<AdminAnomalyIncidentItem> result = service.getIncidents(null, null, null, null, null, 0, 20);

            assertThat(result.content()).hasSize(1);
            assertThat(result.totalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("응답이 없는 상황은 빈 배열이다 - null이 아니다")
        void 응답_없으면_빈배열() {
            AnomalyIncident target = incident(AnomalyReviewStatus.PENDING);
            when(incidentRepository.searchForAdmin(any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(target), PageRequest.of(0, 20), 1));

            PageResponse<AdminAnomalyIncidentItem> result = service.getIncidents(null, null, null, null, null, 0, 20);

            assertThat(result.content().get(0).feedbacks()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("보호자 응답 내역이 함께 온다 - 다수결 상태값에 가려진 소수 의견을 보여 준다")
        void 응답_내역_포함() {
            AnomalyIncident target = incident(AnomalyReviewStatus.CONFLICTED);
            when(incidentRepository.searchForAdmin(any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(target), PageRequest.of(0, 20), 1));
            when(feedbackRepository.findByIncidentIdIn(any())).thenReturn(List.of(
                    feedback("GD0001", AnomalyVerdict.REAL),
                    feedback("GD0002", AnomalyVerdict.FALSE_ALARM)));

            PageResponse<AdminAnomalyIncidentItem> result = service.getIncidents(
                    AnomalyReviewStatus.CONFLICTED, null, null, null, null, 0, 20);

            assertThat(result.content().get(0).feedbacks()).hasSize(2);
        }

        @Test
        @DisplayName("빈 페이지여도 전체 건수 같은 페이징 정보는 살아 있다")
        void 빈_페이지() {
            when(incidentRepository.searchForAdmin(any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(5, 20), 3));

            PageResponse<AdminAnomalyIncidentItem> result = service.getIncidents(null, null, null, null, null, 5, 20);

            assertThat(result.content()).isEmpty();
            assertThat(result.totalElements()).isEqualTo(3);
        }

        @Test
        @DisplayName("페이지 크기는 50을 넘지 않는다")
        void 페이지_크기_상한() {
            when(incidentRepository.searchForAdmin(any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                    .thenReturn(Page.empty(PageRequest.of(0, 50)));

            service.getIncidents(null, null, null, null, null, 0, 500);

            verify(incidentRepository).searchForAdmin(any(), any(), any(), any(), anyBoolean(), any(), any(),
                    eq(PageRequest.of(0, 50)));
        }
    }

    @Nested
    @DisplayName("목록 필터 - 기간·유형·검색어")
    class Filters {

        @BeforeEach
        void emptyPage() {
            when(incidentRepository.searchForAdmin(any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                    .thenReturn(Page.empty(PageRequest.of(0, 20)));
        }

        @Test
        @DisplayName("기간을 생략하면 하한 없이(서비스 이전 시각부터) 조회한다 - 하위호환")
        void 기간_생략은_전체() {
            service.getIncidents(null, null, null, null, null, 0, 20);

            ArgumentCaptor<OffsetDateTime> from = ArgumentCaptor.forClass(OffsetDateTime.class);
            verify(incidentRepository).searchForAdmin(any(), any(), from.capture(), any(), eq(false), any(), any(), any());
            assertThat(from.getValue()).isBefore(OffsetDateTime.of(2001, 1, 1, 0, 0, 0, 0, KST));
        }

        @Test
        @DisplayName("유형 필터는 DetectedType으로 옮겨 넘긴다")
        void 유형_필터() {
            service.getIncidents(null, null, null, AdminAnomalyTypeFilter.FIRE, null, 0, 20);

            verify(incidentRepository).searchForAdmin(any(), any(), any(), eq(DetectedType.FIRE),
                    anyBoolean(), any(), any(), any());
        }

        @Test
        @DisplayName("검색어는 LIKE 메타문자를 이스케이프하고 소문자로 바꿔 이름·위치 검색에 넘긴다")
        void 검색어_이스케이프() {
            when(userRepository.findIdsByNameContaining(any())).thenReturn(List.of("WD0001"));
            when(cameraService.findSessionIdsByLabelKeyword(any())).thenReturn(List.of());

            service.getIncidents(null, null, null, null, "  50%_Kim\\ ", 0, 20);

            verify(userRepository).findIdsByNameContaining("50\\%\\_kim\\\\");
            verify(cameraService).findSessionIdsByLabelKeyword("50\\%\\_kim\\\\");
        }

        @Test
        @DisplayName("검색 결과가 없는 쪽은 매칭 불가능한 값 하나로 채운다 - 빈 IN 절을 만들지 않는다")
        void 빈_검색결과() {
            when(userRepository.findIdsByNameContaining(any())).thenReturn(List.of("WD0001"));
            when(cameraService.findSessionIdsByLabelKeyword(any())).thenReturn(List.of());

            service.getIncidents(null, null, null, null, "김", 0, 20);

            verify(incidentRepository).searchForAdmin(any(), any(), any(), any(), eq(true),
                    eq(List.of("WD0001")), eq(List.of("")), any());
        }

        @Test
        @DisplayName("공백 검색어는 무시한다 - 이름·위치 조회를 돌리지 않는다")
        void 공백_검색어_무시() {
            service.getIncidents(null, null, null, null, "   ", 0, 20);

            verify(userRepository, never()).findIdsByNameContaining(any());
            verify(incidentRepository).searchForAdmin(any(), any(), any(), any(), eq(false), any(), any(), any());
        }

        @Test
        @DisplayName("감지 지속은 마지막 감지 - 첫 감지(분)이고, 한 번만 잡힌 상황은 0이다")
        void 감지_지속() {
            AnomalyIncident once = incident(AnomalyReviewStatus.PENDING);
            AnomalyIncident longer = incident(AnomalyReviewStatus.PENDING);
            longer.addDetection(OffsetDateTime.of(2026, 9, 2, 9, 46, 30, 0, KST), 0.9);
            when(incidentRepository.searchForAdmin(any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(once, longer), PageRequest.of(0, 20), 2));

            PageResponse<AdminAnomalyIncidentItem> result = service.getIncidents(null, null, null, null, null, 0, 20);

            assertThat(result.content()).extracting(AdminAnomalyIncidentItem::durationMinutes).containsExactly(0L, 46L);
        }
    }

    @Nested
    @DisplayName("집계 - 탭 건수·응답률·판정별 현황·AI 신뢰도")
    class Summary {

        /** confidence 합계는 건수 × 0.8 - AI 신뢰도를 보지 않는 테스트용. */
        private AnomalyIncidentRepository.TypeStatusCount row(DetectedType type, AnomalyReviewStatus status, long total) {
            return row(type, status, total, total * 0.8);
        }

        private AnomalyIncidentRepository.TypeStatusCount row(DetectedType type, AnomalyReviewStatus status, long total,
                                                             double confidenceSum) {
            return new AnomalyIncidentRepository.TypeStatusCount() {
                public DetectedType getDetectedType() { return type; }
                public AnomalyReviewStatus getReviewStatus() { return status; }
                public long getTotal() { return total; }
                public double getConfidenceSum() { return confidenceSum; }
            };
        }

        private void rows(AnomalyIncidentRepository.TypeStatusCount... rows) {
            when(incidentRepository.countForAdminSummary(any(), anyBoolean(), any(), any())).thenReturn(List.of(rows));
        }

        @Test
        @DisplayName("응답률 = (전체 - 미판정) / 전체, AI 신뢰도 = 위험·오탐 상황의 confidence 평균 - 동수·미판정은 분모에서 뺀다")
        void 비율_계산() {
            // 시안 숫자: 위험 평균 83% · 오탐 평균 79% → 전체 평균 (16×0.83 + 24×0.79) / 40 = 80.6%
            rows(row(DetectedType.FIRE, AnomalyReviewStatus.PENDING, 12, 12 * 0.3),
                    row(DetectedType.FIRE, AnomalyReviewStatus.REAL, 16, 16 * 0.83),
                    row(DetectedType.FIRE, AnomalyReviewStatus.FALSE_ALARM, 24, 24 * 0.79),
                    row(DetectedType.FIRE, AnomalyReviewStatus.CONFLICTED, 2, 2 * 0.3));

            AdminAnomalySummaryResponse summary = service.getSummary(AdminAnomalyPeriod.THIS_WEEK, null, null);

            assertThat(summary.period()).isEqualTo(AdminAnomalyPeriod.THIS_WEEK);
            assertThat(summary.total()).isEqualTo(54);
            assertThat(summary.review()).isEqualTo(new AdminAnomalySummaryResponse.ReviewCount(12, 16, 24, 2));
            assertThat(summary.responseRate()).isEqualTo(0.7778);      // 42 / 54
            assertThat(summary.aiConfidence().average()).isEqualTo(0.806);  // 미판정·동수(0.3)가 섞이지 않았다
            assertThat(summary.aiConfidence().basis()).isEqualTo(40);
        }

        @Test
        @DisplayName("분모가 0이면 비율은 null이다 - 0%로 채우지 않는다")
        void 분모_0이면_null() {
            rows(row(DetectedType.FIRE, AnomalyReviewStatus.PENDING, 3));

            AdminAnomalySummaryResponse summary = service.getSummary(null, null, null);

            assertThat(summary.period()).isEqualTo(AdminAnomalyPeriod.ALL);
            assertThat(summary.responseRate()).isEqualTo(0.0);          // 응답 0 / 전체 3 - 알 수 있는 값
            assertThat(summary.aiConfidence().average()).isNull();      // 판정 0건 - 알 수 없는 값
            assertThat(summary.aiConfidence().basis()).isZero();

            rows();
            AdminAnomalySummaryResponse empty = service.getSummary(null, null, null);
            assertThat(empty.total()).isZero();
            assertThat(empty.responseRate()).isNull();
            assertThat(empty.byType()).isEmpty();
        }

        @Test
        @DisplayName("유형 탭 건수는 유형 필터를 무시하고, 나머지 집계는 고른 유형으로 좁힌다")
        void 유형_탭() {
            rows(row(DetectedType.FIRE, AnomalyReviewStatus.REAL, 5),
                    row(DetectedType.FIRE, AnomalyReviewStatus.FALSE_ALARM, 5),
                    row(DetectedType.FALL, AnomalyReviewStatus.REAL, 20),
                    row(DetectedType.FALL, AnomalyReviewStatus.PENDING, 5));

            AdminAnomalySummaryResponse summary = service.getSummary(null, AdminAnomalyTypeFilter.FIRE, null);

            assertThat(summary.byType())
                    .extracting(AdminAnomalySummaryResponse.TypeCount::type, AdminAnomalySummaryResponse.TypeCount::label,
                            AdminAnomalySummaryResponse.TypeCount::count)
                    .containsExactly(tuple(DetectedType.FALL, "낙상", 25L), tuple(DetectedType.FIRE, "화재", 10L));
            assertThat(summary.total()).isEqualTo(10);
            assertThat(summary.review()).isEqualTo(new AdminAnomalySummaryResponse.ReviewCount(0, 5, 5, 0));
            assertThat(summary.aiConfidence().basis()).isEqualTo(10);  // 낙상 위험 20건은 빠진다
        }

        @Test
        @DisplayName("한쪽 판정만 있어도 평균은 그 판정 건으로 계산된다")
        void 한쪽_판정만() {
            rows(row(DetectedType.FIRE, AnomalyReviewStatus.REAL, 2, 1.8));

            AdminAnomalySummaryResponse.AiConfidence confidence = service.getSummary(null, null, null).aiConfidence();

            assertThat(confidence.average()).isEqualTo(0.9);
            assertThat(confidence.basis()).isEqualTo(2);
        }

        @Test
        @DisplayName("여러 유형을 합칠 때 평균의 평균이 아니라 건수 가중평균이다")
        void 유형_합산_가중평균() {
            rows(row(DetectedType.FIRE, AnomalyReviewStatus.REAL, 1, 0.9),
                    row(DetectedType.FALL, AnomalyReviewStatus.REAL, 3, 1.5));

            // (0.9 + 1.5) / 4 = 0.6 - 유형별 평균(0.9, 0.5)의 평균 0.7이 아니다
            assertThat(service.getSummary(null, null, null).aiConfidence().average()).isEqualTo(0.6);
        }

        @Test
        @DisplayName("집계된 유형만 담는다 - 흉기 0건은 항목이 없다")
        void 영건_유형_없음() {
            rows(row(DetectedType.FIRE, AnomalyReviewStatus.REAL, 1));

            assertThat(service.getSummary(null, null, null).byType())
                    .extracting(AdminAnomalySummaryResponse.TypeCount::type)
                    .containsExactly(DetectedType.FIRE);
        }
    }
}

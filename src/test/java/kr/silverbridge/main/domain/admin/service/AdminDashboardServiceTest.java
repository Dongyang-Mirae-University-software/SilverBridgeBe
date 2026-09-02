package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.dto.AdminOperationDashboardResponse;
import kr.silverbridge.main.domain.admin.dto.AdminSafetyDashboardResponse;
import kr.silverbridge.main.domain.anomaly.client.AiLiveStreamSubscriber;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.announcement.repository.AnnouncementDraftRepository;
import kr.silverbridge.main.domain.camera.repository.CameraRepository;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.inquiry.repository.InquiryRepository;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.DetectedType;
import kr.silverbridge.main.global.enums.InquiryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 관리자 대시보드 집계 검증.
 *
 * <p>여기서 고정하는 것은 숫자 계산이 아니라 <b>"모르는 값을 0으로 채우지 않는다"</b>는 정책이다.
 * 0과 "알 수 없음"을 섞으면 관리자가 장애를 안전으로 오독한다.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminDashboardServiceTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    @Mock private AiLiveStreamSubscriber aiLiveStreamSubscriber;
    @Mock private AnomalyIncidentRepository anomalyIncidentRepository;
    @Mock private AnnouncementDraftRepository announcementDraftRepository;
    @Mock private CameraRepository cameraRepository;
    @Mock private ConnectionRepository connectionRepository;
    @Mock private InquiryRepository inquiryRepository;
    @Mock private UserRepository userRepository;

    private AdminDashboardService service;

    @BeforeEach
    void setUp() {
        service = new AdminDashboardService(
                aiLiveStreamSubscriber, anomalyIncidentRepository, announcementDraftRepository,
                cameraRepository, connectionRepository, inquiryRepository, userRepository);

        // 기본값 - 각 테스트는 관심 있는 것만 덮어쓴다
        when(anomalyIncidentRepository.findByStartedAtGreaterThanEqual(any())).thenReturn(List.of());
        when(inquiryRepository.findOldestCreatedAtByStatus(any())).thenReturn(Optional.empty());
        when(userRepository.findCreatedAtSince(any(), any(), any())).thenReturn(List.of());
    }

    private AnomalyIncident incident(DetectedType type, AnomalyReviewStatus status) {
        AnomalyIncident incident = AnomalyIncident.builder()
                .wardId("WD0001")
                .sessionId("ward_a9cC5f_k3m")
                .detectedType(type)
                .detectedAt(OffsetDateTime.of(2026, 9, 2, 9, 0, 0, 0, KST))
                .confidence(0.87)
                .build();
        incident.applyReviewStatus(status);
        return incident;
    }

    @Nested
    @DisplayName("AI 연결 상태 - 모르는 것을 0으로 채우지 않는다")
    class AiState {

        @Test
        @DisplayName("AI 미연결이면 스트리밍/끊긴 카메라는 null이다 - 0대가 아니라 '알 수 없음'")
        void ai_미연결이면_null() {
            when(aiLiveStreamSubscriber.isConnected()).thenReturn(false);
            when(cameraRepository.count()).thenReturn(29L);

            AdminSafetyDashboardResponse response = service.getSafetyDashboard();

            assertThat(response.aiConnected()).isFalse();
            assertThat(response.streamingCameras()).isNull();
            assertThat(response.safetyEvents().disconnectedCameras()).isNull();
            // 등록 대수 자체는 DB에서 나오므로 그대로 답한다
            assertThat(response.totalCameras()).isEqualTo(29L);
        }

        @Test
        @DisplayName("AI 미연결이어도 나머지 집계는 정상 응답한다 (예외 없음)")
        void ai_미연결이어도_정상응답() {
            when(aiLiveStreamSubscriber.isConnected()).thenReturn(false);
            when(userRepository.countWardsWithoutActiveGuardian(any(), any(), any())).thenReturn(3L);
            when(userRepository.countWardsWithoutCamera(any(), any())).thenReturn(5L);

            AdminSafetyDashboardResponse response = service.getSafetyDashboard();

            assertThat(response.safetyEvents().wardsWithoutGuardian()).isEqualTo(3L);
            assertThat(response.safetyEvents().wardsWithoutCamera()).isEqualTo(5L);
            assertThat(response.subscribedSessions()).isZero();
        }

        @Test
        @DisplayName("AI 연결 시 끊긴 카메라 = 등록 - 구독")
        void ai_연결시_차감() {
            when(aiLiveStreamSubscriber.isConnected()).thenReturn(true);
            when(aiLiveStreamSubscriber.subscribedSessionCount()).thenReturn(27);
            when(cameraRepository.count()).thenReturn(29L);

            AdminSafetyDashboardResponse response = service.getSafetyDashboard();

            assertThat(response.streamingCameras()).isEqualTo(27L);
            assertThat(response.safetyEvents().disconnectedCameras()).isEqualTo(2L);
            assertThat(response.subscribedSessions()).isEqualTo(27);
        }

        @Test
        @DisplayName("구독이 등록 대수보다 많아도 끊긴 카메라가 음수가 되지 않는다")
        void 음수_방지() {
            when(aiLiveStreamSubscriber.isConnected()).thenReturn(true);
            when(aiLiveStreamSubscriber.subscribedSessionCount()).thenReturn(5);
            when(cameraRepository.count()).thenReturn(3L);

            AdminSafetyDashboardResponse response = service.getSafetyDashboard();

            assertThat(response.safetyEvents().disconnectedCameras()).isZero();
        }
    }

    @Nested
    @DisplayName("오늘 이상감지 - 0건인 유형은 항목을 만들지 않는다")
    class TodayAnomaly {

        @Test
        @DisplayName("집계된 유형만 담긴다 - 낙상·흉기가 0으로 끼어들지 않는다")
        void 집계된_유형만() {
            when(aiLiveStreamSubscriber.isConnected()).thenReturn(true);
            when(anomalyIncidentRepository.findByStartedAtGreaterThanEqual(any())).thenReturn(List.of(
                    incident(DetectedType.FIRE, AnomalyReviewStatus.PENDING),
                    incident(DetectedType.FIRE, AnomalyReviewStatus.REAL),
                    incident(DetectedType.SMOKE, AnomalyReviewStatus.FALSE_ALARM)));

            AdminSafetyDashboardResponse response = service.getSafetyDashboard();

            assertThat(response.todayAnomaly().byType())
                    .extracting(AdminSafetyDashboardResponse.TypeCount::detectedType)
                    .containsExactly(DetectedType.FIRE, DetectedType.SMOKE)
                    .doesNotContain(DetectedType.FALL, DetectedType.WEAPON);
            assertThat(response.todayAnomaly().byType().get(0).count()).isEqualTo(2L);
        }

        @Test
        @DisplayName("판정 4종을 모두 내려 오탐률을 응답률과 함께 계산할 수 있게 한다")
        void 판정_4종_집계() {
            when(aiLiveStreamSubscriber.isConnected()).thenReturn(true);
            when(anomalyIncidentRepository.findByStartedAtGreaterThanEqual(any())).thenReturn(List.of(
                    incident(DetectedType.FIRE, AnomalyReviewStatus.PENDING),
                    incident(DetectedType.FIRE, AnomalyReviewStatus.PENDING),
                    incident(DetectedType.FIRE, AnomalyReviewStatus.REAL),
                    incident(DetectedType.SMOKE, AnomalyReviewStatus.FALSE_ALARM),
                    incident(DetectedType.SMOKE, AnomalyReviewStatus.CONFLICTED)));

            AdminSafetyDashboardResponse.ReviewCount review = service.getSafetyDashboard().todayAnomaly().review();

            assertThat(review.pending()).isEqualTo(2L);
            assertThat(review.real()).isEqualTo(1L);
            assertThat(review.falseAlarm()).isEqualTo(1L);
            assertThat(review.conflicted()).isEqualTo(1L);
            // 응답 수 = total - pending = 3 → 프론트가 "응답 3건 중 오탐 1건(전체 5건)"으로 분모를 밝힐 수 있다
            assertThat(service.getSafetyDashboard().todayAnomaly().total()).isEqualTo(5L);
        }

        @Test
        @DisplayName("상황이 0건이면 total 0 + 빈 배열 - 키는 그대로 존재한다")
        void 상황_0건() {
            when(aiLiveStreamSubscriber.isConnected()).thenReturn(true);

            AdminSafetyDashboardResponse.TodayAnomaly today = service.getSafetyDashboard().todayAnomaly();

            assertThat(today.total()).isZero();
            assertThat(today.byType()).isEmpty();
            assertThat(today.review().pending()).isZero();
        }
    }

    @Nested
    @DisplayName("운영 현황")
    class Operation {

        @Test
        @DisplayName("대기 문의가 없으면 대기 시간은 0이 아니라 null이다")
        void 대기문의_없으면_null() {
            when(inquiryRepository.countByStatus(InquiryStatus.WAITING)).thenReturn(0L);

            AdminOperationDashboardResponse.UnansweredInquiries inquiries =
                    service.getOperationDashboard().unansweredInquiries();

            assertThat(inquiries.count()).isZero();
            assertThat(inquiries.longestWaitingHours()).isNull();
        }

        @Test
        @DisplayName("대기 문의가 있으면 가장 오래 기다린 시간(시간 단위)을 함께 준다")
        void 최장_대기시간() {
            when(inquiryRepository.countByStatus(InquiryStatus.WAITING)).thenReturn(2L);
            when(inquiryRepository.findOldestCreatedAtByStatus(InquiryStatus.WAITING))
                    .thenReturn(Optional.of(OffsetDateTime.now(KST).minusHours(31)));

            AdminOperationDashboardResponse.UnansweredInquiries inquiries =
                    service.getOperationDashboard().unansweredInquiries();

            assertThat(inquiries.count()).isEqualTo(2L);
            assertThat(inquiries.longestWaitingHours()).isEqualTo(31L);
        }

        @Test
        @DisplayName("가입 추이는 7일치를 모두 채운다 - 0건인 날도 항목이 있어야 차트에 구멍이 안 생긴다")
        void 가입추이_7일_채움() {
            OffsetDateTime yesterday = OffsetDateTime.now(KST).minusDays(1);
            when(userRepository.findCreatedAtSince(any(), any(), any())).thenReturn(List.of(yesterday));

            List<AdminOperationDashboardResponse.SignupPoint> trend =
                    service.getOperationDashboard().signupTrend();

            assertThat(trend).hasSize(7);
            LocalDate yesterdayDate = AdminDashboardClock.toDate(yesterday);
            assertThat(trend).anySatisfy(point -> {
                assertThat(point.date()).isEqualTo(yesterdayDate);
                assertThat(point.count()).isEqualTo(1L);
            });
            assertThat(trend).filteredOn(point -> point.count() == 0L).isNotEmpty();
            // 날짜는 과거 → 오늘 순서로 정렬돼 있어야 차트가 뒤집히지 않는다
            assertThat(trend).extracting(AdminOperationDashboardResponse.SignupPoint::date).isSorted();
        }

        @Test
        @DisplayName("처리 대기 연결 요청 수는 상위 필드와 pendingItems가 같은 값을 본다")
        void 대기_연결요청_일관성() {
            when(connectionRepository.countByStatus(any())).thenReturn(4L);

            AdminOperationDashboardResponse response = service.getOperationDashboard();

            assertThat(response.pendingConnections()).isEqualTo(4L);
            assertThat(response.pendingItems().connectionRequests()).isEqualTo(4L);
        }
    }
}

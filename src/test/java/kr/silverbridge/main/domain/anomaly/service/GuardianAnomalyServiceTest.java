package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.dto.AnomalyFeedbackResponse;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncidentFeedback;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyVerdict;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentFeedbackRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.DetectedType;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 보호자 이상감지 이력·오탐 응답 검증.
 *
 * <p>핵심은 둘이다 - <b>인가</b>(ACTIVE 연결이 유일한 열람 근거)와 <b>상태 재계산</b>(다수결이 아니라
 * 불일치 자체를 보존한다). 둘 다 코드만 봐서는 의도가 드러나지 않아 테스트로 고정한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class GuardianAnomalyServiceTest {

    private static final String GUARDIAN_ID = "GD0001";
    private static final String OTHER_GUARDIAN_ID = "GD0002";
    private static final String WARD_ID = "WD0001";
    private static final Long INCIDENT_ID = 37L;
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    @Mock private AnomalyIncidentRepository incidentRepository;
    @Mock private AnomalyIncidentFeedbackRepository feedbackRepository;
    @Mock private ConnectionService connectionService;
    @Mock private CameraService cameraService;
    @Mock private UserRepository userRepository;

    private GuardianAnomalyService service;

    @BeforeEach
    void setUp() {
        service = new GuardianAnomalyService(
                incidentRepository, feedbackRepository, connectionService, cameraService, userRepository);
    }

    private AnomalyIncident incident() {
        return AnomalyIncident.builder()
                .wardId(WARD_ID)
                .sessionId("ward_a9cC5f_k3m")
                .detectedType(DetectedType.FIRE)
                .detectedAt(OffsetDateTime.of(2026, 9, 1, 21, 3, 0, 0, KST))
                .confidence(0.87)
                .build();
    }

    private AnomalyIncidentFeedback feedback(String guardianId, AnomalyVerdict verdict) {
        return AnomalyIncidentFeedback.builder()
                .incidentId(INCIDENT_ID)
                .guardianId(guardianId)
                .verdict(verdict)
                .build();
    }

    @Nested
    @DisplayName("인가 - ACTIVE 연결이 유일한 열람 근거")
    class Authorization {

        @Test
        @DisplayName("연결되지 않은 피보호자를 지정해 조회하면 403이다")
        void historyOfUnconnectedWardIsForbidden() {
            when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.getHistory(GUARDIAN_ID, WARD_ID, 0, 20))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANOMALY_NOT_AUTHORIZED);

            verify(incidentRepository, never()).findByWardIdInOrderByStartedAtDesc(any(), any());
        }

        @Test
        @DisplayName("연결되지 않은 피보호자의 상황에 응답하면 403이고, 응답은 저장되지 않는다")
        void feedbackOnUnconnectedWardIsForbidden() {
            when(incidentRepository.findById(INCIDENT_ID)).thenReturn(Optional.of(incident()));
            when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.submitFeedback(GUARDIAN_ID, INCIDENT_ID, AnomalyVerdict.REAL))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANOMALY_NOT_AUTHORIZED);

            verify(feedbackRepository, never()).save(any());
        }

        @Test
        @DisplayName("연결된 피보호자가 없으면 빈 페이지다 - 예외가 아니다")
        void noConnectionReturnsEmptyPage() {
            when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of());

            assertThat(service.getHistory(GUARDIAN_ID, null, 0, 20).content()).isEmpty();
            verify(incidentRepository, never()).findByWardIdInOrderByStartedAtDesc(any(), any());
        }

        @Test
        @DisplayName("없는 상황에 응답하면 404다 - 남의 것이 아니라 존재하지 않는 것이다")
        void feedbackOnMissingIncidentIsNotFound() {
            when(incidentRepository.findById(INCIDENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.submitFeedback(GUARDIAN_ID, INCIDENT_ID, AnomalyVerdict.REAL))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANOMALY_INCIDENT_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("상태 재계산 - 다수결이 아니다")
    class StatusCalculation {

        @Test
        @DisplayName("응답이 없으면 PENDING")
        void emptyIsPending() {
            assertThat(GuardianAnomalyService.calculateStatus(List.of())).isEqualTo(AnomalyReviewStatus.PENDING);
        }

        @Test
        @DisplayName("응답자 전원이 실제 위험이면 REAL")
        void allRealIsReal() {
            assertThat(GuardianAnomalyService.calculateStatus(List.of(AnomalyVerdict.REAL, AnomalyVerdict.REAL)))
                    .isEqualTo(AnomalyReviewStatus.REAL);
        }

        @Test
        @DisplayName("응답자 전원이 오탐이면 FALSE_ALARM")
        void allFalseAlarmIsFalseAlarm() {
            assertThat(GuardianAnomalyService.calculateStatus(
                    List.of(AnomalyVerdict.FALSE_ALARM, AnomalyVerdict.FALSE_ALARM)))
                    .isEqualTo(AnomalyReviewStatus.FALSE_ALARM);
        }

        @Test
        @DisplayName("답이 갈리면 CONFLICTED - 표를 세어 한쪽으로 정하면 불일치 정보가 사라진다")
        void mixedIsConflicted() {
            assertThat(GuardianAnomalyService.calculateStatus(
                    List.of(AnomalyVerdict.REAL, AnomalyVerdict.FALSE_ALARM, AnomalyVerdict.FALSE_ALARM)))
                    .isEqualTo(AnomalyReviewStatus.CONFLICTED);
        }
    }

    @Nested
    @DisplayName("오탐 응답")
    class SubmitFeedback {

        @BeforeEach
        void allowConnection() {
            when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(true);
        }

        @Test
        @DisplayName("첫 응답은 저장되고 상황 상태가 그 답으로 재계산된다")
        void firstFeedbackIsSavedAndRecalculated() {
            AnomalyIncident incident = incident();
            when(incidentRepository.findById(INCIDENT_ID)).thenReturn(Optional.of(incident));
            when(feedbackRepository.findByIncidentId(INCIDENT_ID)).thenReturn(List.of());

            AnomalyFeedbackResponse response =
                    service.submitFeedback(GUARDIAN_ID, INCIDENT_ID, AnomalyVerdict.FALSE_ALARM);

            assertThat(response.reviewStatus()).isEqualTo(AnomalyReviewStatus.FALSE_ALARM);
            assertThat(incident.getReviewStatus()).isEqualTo(AnomalyReviewStatus.FALSE_ALARM);
            verify(feedbackRepository).save(any(AnomalyIncidentFeedback.class));
        }

        @Test
        @DisplayName("다시 응답하면 새 행이 쌓이지 않고 번복된다 - 1인 1표")
        void secondFeedbackOverwritesInsteadOfAdding() {
            AnomalyIncident incident = incident();
            AnomalyIncidentFeedback mine = feedback(GUARDIAN_ID, AnomalyVerdict.REAL);
            when(incidentRepository.findById(INCIDENT_ID)).thenReturn(Optional.of(incident));
            when(feedbackRepository.findByIncidentId(INCIDENT_ID)).thenReturn(List.of(mine));

            AnomalyFeedbackResponse response =
                    service.submitFeedback(GUARDIAN_ID, INCIDENT_ID, AnomalyVerdict.FALSE_ALARM);

            assertThat(mine.getVerdict()).isEqualTo(AnomalyVerdict.FALSE_ALARM);
            assertThat(response.reviewStatus()).isEqualTo(AnomalyReviewStatus.FALSE_ALARM);
            verify(feedbackRepository, never()).save(any());
        }

        @Test
        @DisplayName("다른 보호자와 답이 갈리면 CONFLICTED가 되고 내 응답은 거부되지 않는다")
        void disagreementBecomesConflicted() {
            AnomalyIncident incident = incident();
            when(incidentRepository.findById(INCIDENT_ID)).thenReturn(Optional.of(incident));
            when(feedbackRepository.findByIncidentId(INCIDENT_ID))
                    .thenReturn(List.of(feedback(OTHER_GUARDIAN_ID, AnomalyVerdict.REAL)));

            AnomalyFeedbackResponse response =
                    service.submitFeedback(GUARDIAN_ID, INCIDENT_ID, AnomalyVerdict.FALSE_ALARM);

            assertThat(response.reviewStatus()).isEqualTo(AnomalyReviewStatus.CONFLICTED);
            assertThat(response.myVerdict()).isEqualTo(AnomalyVerdict.FALSE_ALARM);
            assertThat(incident.getReviewStatus()).isEqualTo(AnomalyReviewStatus.CONFLICTED);
        }
    }

    @Test
    @DisplayName("관리자가 확정한 건은 409 - 뒤늦은 보호자 응답으로 조용히 뒤집히지 않는다")
    void adminResolvedIncidentRejectsFeedback() {
        AnomalyIncident incident = incident();
        // 관리자 정정 API는 PR ④ 범위라 아직 없다. 여기서 검증할 것은 "resolvedBy가 차 있으면 거부한다"는
        // 규칙 자체이므로, 그 상태를 직접 만들어 확인한다.
        ReflectionTestUtils.setField(incident, "resolvedBy", "AD0001");
        when(incidentRepository.findById(INCIDENT_ID)).thenReturn(Optional.of(incident));
        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.submitFeedback(GUARDIAN_ID, INCIDENT_ID, AnomalyVerdict.REAL))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANOMALY_ALREADY_RESOLVED);

        verify(feedbackRepository, never()).save(any());
    }
}

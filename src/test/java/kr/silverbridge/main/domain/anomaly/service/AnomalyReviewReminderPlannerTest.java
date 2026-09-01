package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncidentFeedback;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewReminderLog;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyVerdict;
import kr.silverbridge.main.domain.anomaly.entity.GuardianAnomalySetting;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentFeedbackRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyReviewReminderLogRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyReviewSummaryLogRepository;
import kr.silverbridge.main.domain.anomaly.repository.GuardianAnomalySettingRepository;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.DetectedType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 재촉 대상 선정 규칙 검증.
 *
 * <p>이 클래스가 지키는 것은 "누구에게 보내지 <b>않는가</b>"다 - 이미 답한 사람, 누군가 답해 판정이 끝난
 * 상황, 연결이 끊긴 보호자, 설정을 끈 보호자, 마감을 지난 상황. 하나라도 새면 알림 피로로 보호자가
 * 앱 알림을 통째로 꺼버리고, 그때 SOS·화재 알림까지 함께 죽는다.</p>
 *
 * <p>야간 억제 경계는 {@link AnomalyReviewClockTest}가 따로 고정한다.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnomalyReviewReminderPlannerTest {

    private static final String WARD_ID = "WD0001";
    private static final String GUARDIAN_ID = "GD0001";
    private static final String OTHER_GUARDIAN_ID = "GD0002";
    private static final Long INCIDENT_ID = 37L;
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    @Mock private AnomalyIncidentRepository incidentRepository;
    @Mock private AnomalyIncidentFeedbackRepository feedbackRepository;
    @Mock private AnomalyReviewReminderLogRepository reminderLogRepository;
    @Mock private AnomalyReviewSummaryLogRepository summaryLogRepository;
    @Mock private GuardianAnomalySettingRepository settingRepository;
    @Mock private ConnectionService connectionService;
    @Mock private CameraService cameraService;
    @Mock private UserRepository userRepository;

    private AnomalyProperties properties;
    private AnomalyReviewReminderPlanner planner;

    @BeforeEach
    void setUp() {
        properties = new AnomalyProperties();
        // 야간 억제를 꺼 둔다 - 이 테스트가 검증하는 것은 대상 선정이지 시각이 아니다.
        // 억제 구간이 켜져 있으면 실행 시각(실제 현재 시각)에 따라 결과가 흔들린다.
        properties.getReviewReminder().setQuietStart(LocalTime.MIDNIGHT);
        properties.getReviewReminder().setQuietEnd(LocalTime.MIDNIGHT);

        planner = new AnomalyReviewReminderPlanner(
                incidentRepository, feedbackRepository, reminderLogRepository, summaryLogRepository,
                settingRepository, connectionService, cameraService, userRepository, properties);

        when(cameraService.findLabelsBySessionIds(anyCollection())).thenReturn(Map.of("ward_a9cC5f_k3m", "거실"));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(ward()));
    }

    private AnomalyIncident incident() {
        AnomalyIncident incident = AnomalyIncident.builder()
                .wardId(WARD_ID)
                .sessionId("ward_a9cC5f_k3m")
                .detectedType(DetectedType.FIRE)
                .detectedAt(OffsetDateTime.of(2026, 9, 1, 12, 0, 0, 0, KST))
                .confidence(0.87)
                .build();
        ReflectionTestUtils.setField(incident, "id", INCIDENT_ID);
        return incident;
    }

    private User ward() {
        return User.builder().id(WARD_ID).name("김영희").role(Role.WARD).build();
    }

    /** 후보 조회가 이 상황 하나를 돌려주도록 고정한다. */
    private void candidateIsFound(AnomalyIncident incident) {
        when(incidentRepository.findByReviewStatusAndLastDetectedAtLessThanEqualAndStartedAtGreaterThanEqual(
                any(), any(), any())).thenReturn(List.of(incident));
    }

    @Nested
    @DisplayName("건별 재촉 대상 선정")
    class ClaimReminders {

        @Test
        @DisplayName("아무도 응답하지 않은 상황은 ACTIVE 보호자에게 재촉한다")
        void unansweredIncidentIsClaimed() {
            candidateIsFound(incident());
            when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_ID));
            when(feedbackRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of());
            when(reminderLogRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of());
            when(settingRepository.findByGuardianIdIn(anyCollection())).thenReturn(List.of());

            List<AnomalyReviewReminderTarget> targets = planner.claimReminders();

            assertThat(targets).hasSize(1);
            assertThat(targets.getFirst().guardianId()).isEqualTo(GUARDIAN_ID);
            assertThat(targets.getFirst().wardName()).isEqualTo("김영희");
            assertThat(targets.getFirst().cameraLabel()).isEqualTo("거실");
            // 선점 후 발송 — 반환 전에 기록이 저장돼야 한다
            verify(reminderLogRepository).saveAll(anyCollection());
        }

        @Test
        @DisplayName("이미 응답한 보호자에게는 재촉하지 않는다")
        void answeredGuardianIsSkipped() {
            candidateIsFound(incident());
            when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_ID));
            when(feedbackRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of(
                    AnomalyIncidentFeedback.builder()
                            .incidentId(INCIDENT_ID).guardianId(GUARDIAN_ID).verdict(AnomalyVerdict.REAL).build()));
            when(reminderLogRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of());

            assertThat(planner.claimReminders()).isEmpty();
            verify(reminderLogRepository, never()).saveAll(anyCollection());
        }

        @Test
        @DisplayName("이미 재촉한 (상황, 보호자)에는 다시 보내지 않는다 - 5분마다 도는 스케줄러의 반복 방지")
        void alreadyRemindedIsSkipped() {
            candidateIsFound(incident());
            when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_ID));
            when(feedbackRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of());
            when(reminderLogRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of(
                    AnomalyReviewReminderLog.builder()
                            .incidentId(INCIDENT_ID).guardianId(GUARDIAN_ID)
                            .sentAt(OffsetDateTime.now(KST)).build()));

            assertThat(planner.claimReminders()).isEmpty();
        }

        @Test
        @DisplayName("수신 설정을 끈 보호자에게는 보내지 않는다")
        void disabledGuardianIsSkipped() {
            candidateIsFound(incident());
            when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_ID));
            when(feedbackRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of());
            when(reminderLogRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of());
            when(settingRepository.findByGuardianIdIn(anyCollection())).thenReturn(List.of(
                    GuardianAnomalySetting.builder()
                            .guardianId(GUARDIAN_ID).reviewReminderEnabled(false).build()));

            assertThat(planner.claimReminders()).isEmpty();
            verify(reminderLogRepository, never()).saveAll(anyCollection());
        }

        @Test
        @DisplayName("연결이 해제된 보호자에게는 보내지 않는다 - ACTIVE 연결이 유일한 열람 근거다")
        void disconnectedGuardianIsSkipped() {
            candidateIsFound(incident());
            when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of());
            when(feedbackRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of());
            when(reminderLogRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of());

            assertThat(planner.claimReminders()).isEmpty();
        }

        @Test
        @DisplayName("야간에는 선점하지 않는다 - 버리는 게 아니라 다음 아침으로 미룬다")
        void quietHoursClaimNothing() {
            properties.getReviewReminder().setQuietStart(LocalTime.MIDNIGHT);
            properties.getReviewReminder().setQuietEnd(LocalTime.of(23, 59));   // 사실상 하루 종일 억제

            assertThat(planner.claimReminders()).isEmpty();
            verify(incidentRepository, never())
                    .findByReviewStatusAndLastDetectedAtLessThanEqualAndStartedAtGreaterThanEqual(any(), any(), any());
        }

        @Test
        @DisplayName("후보 조회는 PENDING + 닫힘 + 마감 전으로 좁힌다")
        void candidateQueryIsNarrowed() {
            candidateIsFound(incident());
            when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of());
            when(feedbackRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of());
            when(reminderLogRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of());

            planner.claimReminders();

            verify(incidentRepository).findByReviewStatusAndLastDetectedAtLessThanEqualAndStartedAtGreaterThanEqual(
                    org.mockito.ArgumentMatchers.eq(AnomalyReviewStatus.PENDING), any(), any());
        }
    }

    @Nested
    @DisplayName("하루 1회 요약")
    class ClaimSummaries {

        @BeforeEach
        void sendAnyTime() {
            // 요약 시각 게이트를 열어 둔다 - 여기서 볼 것은 "무엇을 세는가"이지 시각이 아니다.
            properties.getReviewReminder().setSummaryTime(LocalTime.MIDNIGHT);
        }

        @Test
        @DisplayName("건별 재촉을 보낸 뒤에도 답이 없으면 요약에 담는다")
        void remindedButUnansweredIsCounted() {
            when(incidentRepository.findByReviewStatusAndStartedAtGreaterThanEqual(any(), any()))
                    .thenReturn(List.of(incident()));
            when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_ID));
            when(feedbackRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of());
            when(reminderLogRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of(
                    AnomalyReviewReminderLog.builder()
                            .incidentId(INCIDENT_ID).guardianId(GUARDIAN_ID)
                            .sentAt(OffsetDateTime.now(KST)).build()));
            when(summaryLogRepository.findBySummaryDateAndGuardianIdIn(any(), anyCollection())).thenReturn(List.of());
            when(settingRepository.findByGuardianIdIn(anyCollection())).thenReturn(List.of());

            List<AnomalyReviewSummaryTarget> targets = planner.claimSummaries();

            assertThat(targets).hasSize(1);
            assertThat(targets.getFirst().pendingCount()).isEqualTo(1);
            verify(summaryLogRepository).saveAll(anyCollection());
        }

        @Test
        @DisplayName("건별 재촉이 아직 안 나간 상황은 요약에 담지 않는다 - 같은 건이 연달아 오면 안 된다")
        void notYetRemindedIsExcluded() {
            when(incidentRepository.findByReviewStatusAndStartedAtGreaterThanEqual(any(), any()))
                    .thenReturn(List.of(incident()));
            when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_ID));
            when(feedbackRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of());
            when(reminderLogRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of());

            assertThat(planner.claimSummaries()).isEmpty();
        }

        @Test
        @DisplayName("오늘 이미 요약을 받은 보호자에게는 다시 보내지 않는다 - 하루 1건")
        void alreadySentTodayIsSkipped() {
            when(incidentRepository.findByReviewStatusAndStartedAtGreaterThanEqual(any(), any()))
                    .thenReturn(List.of(incident()));
            when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_ID));
            when(feedbackRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of());
            when(reminderLogRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of(
                    AnomalyReviewReminderLog.builder()
                            .incidentId(INCIDENT_ID).guardianId(GUARDIAN_ID)
                            .sentAt(OffsetDateTime.now(KST)).build()));
            when(summaryLogRepository.findBySummaryDateAndGuardianIdIn(any(), anyCollection())).thenReturn(List.of(
                    kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewSummaryLog.builder()
                            .guardianId(GUARDIAN_ID)
                            .summaryDate(AnomalyReviewClock.toDate(OffsetDateTime.now(KST)))
                            .pendingCount(1)
                            .sentAt(OffsetDateTime.now(KST))
                            .build()));

            assertThat(planner.claimSummaries()).isEmpty();
            verify(summaryLogRepository, never()).saveAll(anyCollection());
        }

        @Test
        @DisplayName("요약 시각 전에는 보내지 않는다")
        void beforeSummaryTimeSendsNothing() {
            properties.getReviewReminder().setSummaryTime(LocalTime.of(23, 59));

            assertThat(planner.claimSummaries()).isEmpty();
            verify(incidentRepository, never()).findByReviewStatusAndStartedAtGreaterThanEqual(any(), any());
        }
    }
}

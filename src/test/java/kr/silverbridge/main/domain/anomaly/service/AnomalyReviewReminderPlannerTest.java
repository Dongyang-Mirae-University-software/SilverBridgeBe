package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncidentFeedback;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewReminderLog;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyVerdict;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewConflictLog;
import kr.silverbridge.main.domain.anomaly.entity.GuardianAnomalySetting;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentFeedbackRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyReviewConflictLogRepository;
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
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock private AnomalyReviewConflictLogRepository conflictLogRepository;
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
                conflictLogRepository, settingRepository, connectionService, cameraService, userRepository, properties);

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

        /** 오늘 요약에 담을 수 있는 재촉의 마지막 발송 시각(요약 시각 00:00 기준이라 어제 22:00). */
        private OffsetDateTime cutoff() {
            return AnomalyReviewReminderPlanner.summaryCutoff(AnomalyReviewClock.now(), LocalTime.MIDNIGHT);
        }

        private AnomalyReviewReminderLog reminderSentAt(OffsetDateTime sentAt) {
            return AnomalyReviewReminderLog.builder()
                    .incidentId(INCIDENT_ID).guardianId(GUARDIAN_ID).sentAt(sentAt).build();
        }

        /** 상황 1건 + ACTIVE 보호자 1명 + 미응답 + 오늘 요약 없음 + 설정 기본값으로 고정하고, 재촉 발송 시각만 바꾼다. */
        private void givenRemindedAt(OffsetDateTime sentAt) {
            when(incidentRepository.findByReviewStatusAndStartedAtGreaterThanEqual(any(), any()))
                    .thenReturn(List.of(incident()));
            when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_ID));
            when(feedbackRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of());
            givenReminderLogs(reminderSentAt(sentAt));
            when(summaryLogRepository.findBySummaryDateAndGuardianIdIn(any(), anyCollection())).thenReturn(List.of());
            when(settingRepository.findByGuardianIdIn(anyCollection())).thenReturn(List.of());
        }

        @Test
        @DisplayName("요약 기준선 = 오늘(KST) 요약 시각 - 2시간 (20:00이면 18:00) (ANOM-G09)")
        void cutoffIsSummaryTimeMinusGap() {
            OffsetDateTime at2005 = OffsetDateTime.of(2026, 10, 2, 20, 5, 0, 0, KST);

            assertThat(AnomalyReviewReminderPlanner.summaryCutoff(at2005, LocalTime.of(20, 0)))
                    .isEqualTo(OffsetDateTime.of(2026, 10, 2, 18, 0, 0, 0, KST));
            // UTC로 들어와도 KST 날짜 기준이다 (UTC 10/2 15:30 = KST 10/3 00:30 → 10/3 18:00)
            assertThat(AnomalyReviewReminderPlanner.summaryCutoff(
                    OffsetDateTime.of(2026, 10, 2, 15, 30, 0, 0, ZoneOffset.UTC), LocalTime.of(20, 0)))
                    .isEqualTo(OffsetDateTime.of(2026, 10, 3, 18, 0, 0, 0, KST));
        }

        @Test
        @DisplayName("같은 주기에 방금 건별 재촉이 나간 상황은 오늘 요약에 담지 않는다 - 연달아 도착 방지 (ANOM-G09)")
        void justRemindedIsDeferred() {
            givenRemindedAt(AnomalyReviewClock.now());

            assertThat(planner.claimSummaries()).isEmpty();
            // 선점 기록도 남기지 않는다 - 남기면 다음 날 요약까지 막힌다(하루 1건 UNIQUE)
            verify(summaryLogRepository, never()).saveAll(anyCollection());
        }

        @Test
        @DisplayName("경계: 기준선과 같은 시각에 재촉한 상황은 담고, 1초 뒤면 다음 날로 넘긴다 (ANOM-G09)")
        void cutoffBoundary() {
            givenRemindedAt(cutoff());
            assertThat(planner.claimSummaries()).hasSize(1);

            givenRemindedAt(cutoff().plusSeconds(1));
            assertThat(planner.claimSummaries()).isEmpty();
        }

        /**
         * 재촉 기록 저장소를 실제처럼 흉내 낸다 - 두 조회(상황 기준·보호자+시각 기준)가 <b>같은 기록 묶음</b>을 본다.
         * 같은 주기에 앞서 커밋된 건별 재촉 선점 기록이 요약 판단에 그대로 보이는 상황을 재현하기 위해서다.
         */
        private void givenReminderLogs(AnomalyReviewReminderLog... logs) {
            List<AnomalyReviewReminderLog> all = List.of(logs);
            when(reminderLogRepository.findByIncidentIdIn(anyCollection())).thenAnswer(inv -> {
                java.util.Collection<Long> ids = inv.getArgument(0);
                return all.stream().filter(log -> ids.contains(log.getIncidentId())).toList();
            });
            when(reminderLogRepository.findByGuardianIdInAndSentAtAfter(anyCollection(), any())).thenAnswer(inv -> {
                java.util.Collection<String> guardianIds = inv.getArgument(0);
                OffsetDateTime after = inv.getArgument(1);
                return all.stream()
                        .filter(log -> guardianIds.contains(log.getGuardianId()) && log.getSentAt().isAfter(after))
                        .toList();
            });
        }

        private AnomalyIncident secondIncident() {
            AnomalyIncident recent = AnomalyIncident.builder()
                    .wardId(WARD_ID).sessionId("ward_a9cC5f_k3m").detectedType(DetectedType.FIRE)
                    .detectedAt(OffsetDateTime.of(2026, 9, 1, 13, 0, 0, 0, KST)).confidence(0.9).build();
            ReflectionTestUtils.setField(recent, "id", INCIDENT_ID + 1);
            return recent;
        }

        /** 상황 2건(오래된 A + 새 B), 보호자 1명, 미응답, 오늘 요약 없음, 설정 기본값. */
        private void givenTwoPendingIncidents() {
            when(incidentRepository.findByReviewStatusAndStartedAtGreaterThanEqual(any(), any()))
                    .thenReturn(List.of(incident(), secondIncident()));
            when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_ID));
            when(feedbackRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of());
            when(summaryLogRepository.findBySummaryDateAndGuardianIdIn(any(), anyCollection())).thenReturn(List.of());
            when(settingRepository.findByGuardianIdIn(anyCollection())).thenReturn(List.of());
        }

        @Test
        @DisplayName("같은 주기에 건별 재촉을 받은 보호자는 다른 오래된 미응답 상황이 있어도 요약을 미룬다 (ANOM-G09 재현)")
        void sameCycleReminderDefersGuardianSummary() {
            // QA 시나리오: 20시대, 오래된 미응답 상황 A(이미 재촉함) + 75분 전에 닫힌 상황 B.
            // 같은 실행에서 B 건별 재촉이 먼저 선점·커밋되고, 이어지는 요약 판단이 그 기록을 본다.
            givenTwoPendingIncidents();
            givenReminderLogs(
                    reminderSentAt(cutoff().minusHours(5)),
                    AnomalyReviewReminderLog.builder().incidentId(INCIDENT_ID + 1).guardianId(GUARDIAN_ID)
                            .sentAt(AnomalyReviewClock.now()).build());

            assertThat(planner.claimSummaries()).isEmpty();
            // 선점 기록을 남기지 않는다 - 남기면 오늘 요약이 "보낸 것"으로 굳어 간격이 찬 뒤에도 못 보낸다(하루 1건 UNIQUE)
            verify(summaryLogRepository, never()).saveAll(anyCollection());
        }

        @Test
        @DisplayName("이미 답해 PENDING을 벗어난 상황의 최근 재촉도 요약을 미룬다 - 그 푸시는 이미 나갔다 (ANOM-G09)")
        void recentReminderOfAnsweredIncidentAlsoDefers() {
            when(incidentRepository.findByReviewStatusAndStartedAtGreaterThanEqual(any(), any()))
                    .thenReturn(List.of(incident()));
            when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_ID));
            when(feedbackRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of());
            when(summaryLogRepository.findBySummaryDateAndGuardianIdIn(any(), anyCollection())).thenReturn(List.of());
            when(settingRepository.findByGuardianIdIn(anyCollection())).thenReturn(List.of());
            // 후보(PENDING)에 없는 상황 99의 재촉이 방금 나갔다
            givenReminderLogs(
                    reminderSentAt(cutoff().minusHours(5)),
                    AnomalyReviewReminderLog.builder().incidentId(99L).guardianId(GUARDIAN_ID)
                            .sentAt(AnomalyReviewClock.now()).build());

            assertThat(planner.claimSummaries()).isEmpty();
        }

        @Test
        @DisplayName("다른 보호자가 받은 최근 재촉은 이 보호자의 요약을 막지 않는다 (ANOM-G09)")
        void otherGuardiansRecentReminderDoesNotDefer() {
            givenTwoPendingIncidents();
            givenReminderLogs(
                    reminderSentAt(cutoff().minusHours(5)),
                    AnomalyReviewReminderLog.builder().incidentId(INCIDENT_ID + 1).guardianId(OTHER_GUARDIAN_ID)
                            .sentAt(AnomalyReviewClock.now()).build());

            List<AnomalyReviewSummaryTarget> targets = planner.claimSummaries();

            assertThat(targets).singleElement().satisfies(target -> {
                assertThat(target.guardianId()).isEqualTo(GUARDIAN_ID);
                assertThat(target.pendingCount()).isEqualTo(1);
            });
        }

        @Test
        @DisplayName("보호자 단위 미루기 기준은 지금 - 2시간이다 (ANOM-G09)")
        void deferWindowIsNowMinusGap() {
            givenRemindedAt(cutoff().minusHours(1));
            OffsetDateTime before = AnomalyReviewClock.now();

            planner.claimSummaries();

            ArgumentCaptor<OffsetDateTime> captor = ArgumentCaptor.forClass(OffsetDateTime.class);
            verify(reminderLogRepository).findByGuardianIdInAndSentAtAfter(eq(java.util.Set.of(GUARDIAN_ID)), captor.capture());
            assertThat(captor.getValue())
                    .isAfterOrEqualTo(before.minus(AnomalyReviewReminderPlanner.SUMMARY_MIN_GAP_AFTER_REMINDER))
                    .isBeforeOrEqualTo(AnomalyReviewClock.now().minus(AnomalyReviewReminderPlanner.SUMMARY_MIN_GAP_AFTER_REMINDER));
        }

        @Test
        @DisplayName("건별 재촉을 보낸 뒤에도 답이 없으면 요약에 담는다")
        void remindedButUnansweredIsCounted() {
            when(incidentRepository.findByReviewStatusAndStartedAtGreaterThanEqual(any(), any()))
                    .thenReturn(List.of(incident()));
            when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_ID));
            when(feedbackRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of());
            when(reminderLogRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of(
                    reminderSentAt(cutoff().minusHours(1))));
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
                    reminderSentAt(cutoff().minusHours(1))));
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

    @Nested
    @DisplayName("동수 재확인 안내 대상 선정")
    class ClaimConflicts {

        private static final String NOT_ANSWERED_GUARDIAN_ID = "GD0003";

        private AnomalyIncident conflicted() {
            AnomalyIncident incident = incident();
            incident.applyReviewStatus(AnomalyReviewStatus.CONFLICTED);
            return incident;
        }

        private AnomalyIncidentFeedback answer(String guardianId, AnomalyVerdict verdict) {
            return AnomalyIncidentFeedback.builder()
                    .incidentId(INCIDENT_ID).guardianId(guardianId).verdict(verdict).build();
        }

        /** 동수 상황 하나 + 응답자 둘(GUARDIAN·OTHER) + 미응답 보호자 하나가 ACTIVE로 연결된 기본 상태. */
        private void tieWithTwoRespondents() {
            when(incidentRepository.findByReviewStatusAndStartedAtGreaterThanEqual(
                    eq(AnomalyReviewStatus.CONFLICTED), any())).thenReturn(List.of(conflicted()));
            when(feedbackRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of(
                    answer(GUARDIAN_ID, AnomalyVerdict.REAL), answer(OTHER_GUARDIAN_ID, AnomalyVerdict.FALSE_ALARM)));
            when(connectionService.getActiveGuardianIds(WARD_ID))
                    .thenReturn(List.of(GUARDIAN_ID, OTHER_GUARDIAN_ID, NOT_ANSWERED_GUARDIAN_ID));
            when(settingRepository.findByGuardianIdIn(anyCollection())).thenReturn(List.of());
        }

        @Test
        @DisplayName("응답한 보호자에게만 안내한다 - 동수를 만든 보호자(sent=false 기록)와 미응답 보호자는 빠진다")
        void onlyOtherRespondentsAreClaimed() {
            tieWithTwoRespondents();
            // 동수를 만든 GUARDIAN은 응답 트랜잭션이 이미 sent=false로 기록해 두었다.
            when(conflictLogRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of(
                    AnomalyReviewConflictLog.builder().incidentId(INCIDENT_ID).guardianId(GUARDIAN_ID)
                            .sent(false).createdAt(OffsetDateTime.now(KST)).build()));

            List<AnomalyReviewReminderTarget> targets = planner.claimConflicts();

            assertThat(targets).extracting(AnomalyReviewReminderTarget::guardianId).containsExactly(OTHER_GUARDIAN_ID);
            assertThat(targets.getFirst().wardName()).isEqualTo("김영희");

            // 선점 후 발송 - 반환 전에 sent=true 기록이 저장돼야 한다
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<AnomalyReviewConflictLog>> captor = ArgumentCaptor.forClass(List.class);
            verify(conflictLogRepository).saveAll(captor.capture());
            assertThat(captor.getValue()).singleElement()
                    .satisfies(log -> {
                        assertThat(log.getGuardianId()).isEqualTo(OTHER_GUARDIAN_ID);
                        assertThat(log.isSent()).isTrue();
                    });
        }

        @Test
        @DisplayName("동수 상황의 미응답 보호자에게는 건별 재촉도 동수 안내도 가지 않는다 - 의도된 동작 (ANOM-G06)")
        void unansweredGuardianOfTieGetsNothing() {
            // 저장소를 실제처럼 흉내 낸다 - 상태 조건에 맞는 상황만 돌려준다. 이 상황은 동수(CONFLICTED)다.
            AnomalyIncident tie = conflicted();
            when(incidentRepository.findByReviewStatusAndLastDetectedAtLessThanEqualAndStartedAtGreaterThanEqual(
                    any(), any(), any())).thenAnswer(inv ->
                    inv.getArgument(0) == tie.getReviewStatus() ? List.of(tie) : List.of());
            when(incidentRepository.findByReviewStatusAndStartedAtGreaterThanEqual(any(), any())).thenAnswer(inv ->
                    inv.getArgument(0) == tie.getReviewStatus() ? List.of(tie) : List.of());
            when(feedbackRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of(
                    answer(GUARDIAN_ID, AnomalyVerdict.REAL), answer(OTHER_GUARDIAN_ID, AnomalyVerdict.FALSE_ALARM)));
            when(connectionService.getActiveGuardianIds(WARD_ID))
                    .thenReturn(List.of(GUARDIAN_ID, OTHER_GUARDIAN_ID, NOT_ANSWERED_GUARDIAN_ID));
            when(reminderLogRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of());
            when(conflictLogRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of());
            when(settingRepository.findByGuardianIdIn(anyCollection())).thenReturn(List.of());

            // 건별 재촉은 PENDING 상황만 본다 - 한 명이라도 답해 PENDING을 벗어나면 나머지 재촉은 멈춘다
            assertThat(planner.claimReminders()).isEmpty();
            verify(reminderLogRepository, never()).saveAll(anyCollection());

            // 동수 안내는 응답한 보호자에게만 간다
            assertThat(planner.claimConflicts())
                    .extracting(AnomalyReviewReminderTarget::guardianId)
                    .containsExactlyInAnyOrder(GUARDIAN_ID, OTHER_GUARDIAN_ID)
                    .doesNotContain(NOT_ANSWERED_GUARDIAN_ID);
        }

        @Test
        @DisplayName("이미 처리한 보호자에게는 다시 보내지 않는다 - 동수를 오가도 보호자당 한 번")
        void alreadyHandledIsSkipped() {
            tieWithTwoRespondents();
            when(conflictLogRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of(
                    AnomalyReviewConflictLog.builder().incidentId(INCIDENT_ID).guardianId(GUARDIAN_ID)
                            .sent(false).createdAt(OffsetDateTime.now(KST)).build(),
                    AnomalyReviewConflictLog.builder().incidentId(INCIDENT_ID).guardianId(OTHER_GUARDIAN_ID)
                            .sent(true).createdAt(OffsetDateTime.now(KST)).build()));

            assertThat(planner.claimConflicts()).isEmpty();
            verify(conflictLogRepository, never()).saveAll(anyCollection());
        }

        @Test
        @DisplayName("연결이 해제된 응답자에게는 보내지 않는다 - ACTIVE 연결이 유일한 열람 근거다")
        void disconnectedRespondentIsSkipped() {
            tieWithTwoRespondents();
            when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of());
            when(conflictLogRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of());

            assertThat(planner.claimConflicts()).isEmpty();
        }

        @Test
        @DisplayName("재촉 수신 설정을 끈 응답자에게는 보내지 않는다")
        void disabledRespondentIsSkipped() {
            tieWithTwoRespondents();
            when(conflictLogRepository.findByIncidentIdIn(anyCollection())).thenReturn(List.of());
            when(settingRepository.findByGuardianIdIn(anyCollection())).thenReturn(List.of(
                    GuardianAnomalySetting.builder().guardianId(GUARDIAN_ID).reviewReminderEnabled(false).build(),
                    GuardianAnomalySetting.builder().guardianId(OTHER_GUARDIAN_ID).reviewReminderEnabled(false).build()));

            assertThat(planner.claimConflicts()).isEmpty();
            verify(conflictLogRepository, never()).saveAll(anyCollection());
        }

        @Test
        @DisplayName("야간에는 선점하지 않는다 - 다음 아침으로 미룬다")
        void quietHoursClaimNothing() {
            properties.getReviewReminder().setQuietStart(LocalTime.MIDNIGHT);
            properties.getReviewReminder().setQuietEnd(LocalTime.of(23, 59));   // 사실상 하루 종일 억제

            assertThat(planner.claimConflicts()).isEmpty();
            verify(incidentRepository, never()).findByReviewStatusAndStartedAtGreaterThanEqual(any(), any());
        }
    }
}

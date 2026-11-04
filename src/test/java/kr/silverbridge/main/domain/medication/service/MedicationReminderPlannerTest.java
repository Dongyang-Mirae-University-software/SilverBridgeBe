package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.medication.config.MedicationProperties;
import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.entity.MedicationIntake;
import kr.silverbridge.main.domain.medication.entity.MedicationReminderLog;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import kr.silverbridge.main.domain.medication.entity.GuardianMedicationSetting;
import kr.silverbridge.main.domain.medication.repository.GuardianMedicationSettingRepository;
import kr.silverbridge.main.domain.medication.repository.MedicationIntakeRepository;
import kr.silverbridge.main.domain.medication.repository.MedicationReminderLogRepository;
import kr.silverbridge.main.domain.medication.repository.MedicationRepository;
import kr.silverbridge.main.domain.notification.service.GuardianNotificationPreferenceService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MedicationReminderPlanner 단위 테스트 — <b>누구에게 보낼지 고르고, 두 번 보내지 않는지</b>가 핵심이다.
 *
 * <p>검증 축 — ① 미복용·설정 ON인 약만 대상 ② 이미 보낸 회차는 다시 보내지 않음(발송 기록 선점)
 * ③ 유예 창 경계 ④ 재알림 조건(지연·마감·재알림 설정·중간 체크) ⑤ ACTIVE 보호자가 없는 피보호자 제외(MED-G01)
 * ⑥ 보호자 "복약 알림" 설정 - 켠 이용 중 보호자가 한 명이라도 있어야 보낸다(2026-11-04).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // 분기별로 쓰이지 않는 스텁이 생긴다(조회 순서에 따라 조기 반환)
class MedicationReminderPlannerTest {

    @Mock private MedicationRepository medicationRepository;
    @Mock private MedicationIntakeRepository intakeRepository;
    @Mock private MedicationReminderLogRepository reminderLogRepository;
    @Mock private MedicationSettingService settingService;
    @Mock private ConnectionRepository connectionRepository;
    @Mock private UserRepository userRepository;
    @Mock private GuardianNotificationPreferenceService preferenceService;
    @Mock private GuardianMedicationSettingRepository guardianSettingRepository;

    private MedicationProperties properties;
    private MedicationReminderPlanner planner;

    private static final String WARD_ID = "WD0001";
    private static final String UNGUARDED_WARD_ID = "WD0002";

    @BeforeEach
    void setUp() {
        properties = new MedicationProperties();
        planner = new MedicationReminderPlanner(
                medicationRepository, intakeRepository, reminderLogRepository, settingService,
                connectionRepository, userRepository, preferenceService, guardianSettingRepository, properties);
        // 기본 시나리오: WARD_ID에는 ACTIVE 보호자가 있고, 보호자는 이용 중이며 복약 알림 설정 행이 없다(=켜짐)
        when(connectionRepository.findByParticipantsAndStatusIn(any(), any()))
                .thenReturn(List.of(activeConnection("GD0001", WARD_ID)));
        when(userRepository.findAllById(any())).thenAnswer(invocation -> {
            Iterable<String> ids = invocation.getArgument(0);
            List<User> users = new java.util.ArrayList<>();
            ids.forEach(id -> users.add(guardian(id, Status.ACTIVE)));
            return users;
        });
        when(preferenceService.medicationDisabledGuardians()).thenReturn(Set.of());
        when(guardianSettingRepository.findByWardIdInAndMissedAlertEnabledFalse(any())).thenReturn(List.of());
    }

    // ─── 최초 발송 ──────────────────────────────────────────────────

    @Test
    @DisplayName("미복용인 약을 대상으로 잡고 발송 기록을 먼저 남긴다(선점)")
    void claimFirst_대상선정_선점() {
        Medication target = medication(1L, "혈압약");
        when(medicationRepository.findByDeletedAtIsNullAndDoseTimeBetween(any(), any()))
                .thenReturn(List.of(target));
        when(intakeRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());
        when(reminderLogRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());

        List<MedicationReminderTarget> claimed = planner.claimFirstReminders();

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).medicationId()).isEqualTo(1L);
        assertThat(claimed.get(0).attempt()).isEqualTo(MedicationReminderLog.ATTEMPT_FIRST);

        ArgumentCaptor<List<MedicationReminderLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(reminderLogRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement()
                .satisfies(log -> {
                    assertThat(log.getMedicationId()).isEqualTo(1L);
                    assertThat(log.getAttempt()).isEqualTo(MedicationReminderLog.ATTEMPT_FIRST);
                    assertThat(log.getDoseDate()).isEqualTo(MedicationClock.today());
                });
    }

    @Test
    @DisplayName("이미 복용 체크된 약은 알리지 않는다")
    void claimFirst_이미복용_스킵() {
        when(medicationRepository.findByDeletedAtIsNullAndDoseTimeBetween(any(), any()))
                .thenReturn(List.of(medication(1L, "혈압약")));
        when(intakeRepository.findByMedicationIdInAndDoseDate(any(), any()))
                .thenReturn(List.of(MedicationIntake.of(1L, MedicationClock.today(), OffsetDateTime.now())));
        when(reminderLogRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());

        assertThat(planner.claimFirstReminders()).isEmpty();
        verify(reminderLogRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("이미 최초 알림을 보낸 약은 다시 보내지 않는다 — 1분 주기 반복 발송 방지")
    void claimFirst_이미발송_스킵() {
        when(medicationRepository.findByDeletedAtIsNullAndDoseTimeBetween(any(), any()))
                .thenReturn(List.of(medication(1L, "혈압약")));
        when(intakeRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());
        when(reminderLogRepository.findByMedicationIdInAndDoseDate(any(), any()))
                .thenReturn(List.of(MedicationReminderLog.of(
                        1L, MedicationClock.today(), MedicationReminderLog.ATTEMPT_FIRST, OffsetDateTime.now())));

        assertThat(planner.claimFirstReminders()).isEmpty();
        verify(reminderLogRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("최초 알림은 피보호자 단위 옛 스위치(alarm_enabled)를 읽지 않는다 (2026-10-24) - 켜고 끄는 건 보호자 설정이다(2026-11-04)")
    void claimFirst_알림설정_보지않음() {
        when(medicationRepository.findByDeletedAtIsNullAndDoseTimeBetween(any(), any()))
                .thenReturn(List.of(medication(1L, "혈압약")));
        when(intakeRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());
        when(reminderLogRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());

        assertThat(planner.claimFirstReminders()).hasSize(1);
        // 피보호자 단위 스위치·API는 없앴다 - 발송 여부는 보호자 복약 알림 설정만 가른다
        verify(settingService, never()).findPreferences(any());
    }

    @Test
    @DisplayName("유예 창 = [현재-유예, 현재] — 계산된 구간을 그대로 조회에 넘긴다")
    void claimFirst_유예창_조회구간() {
        when(medicationRepository.findByDeletedAtIsNullAndDoseTimeBetween(any(), any())).thenReturn(List.of());

        planner.claimFirstReminders();

        ArgumentCaptor<LocalTime> from = ArgumentCaptor.forClass(LocalTime.class);
        ArgumentCaptor<LocalTime> to = ArgumentCaptor.forClass(LocalTime.class);
        verify(medicationRepository).findByDeletedAtIsNullAndDoseTimeBetween(from.capture(), to.capture());

        // 이 테스트는 "배선"만 본다 — 상한이 현재 시각이고, 하한이 그 상한으로 계산한 유예 창 시작인지.
        // 경계 규칙 자체(자정 절단 포함)는 아래 graceWindowStart_* 테스트가 시각과 무관하게 검증한다.
        assertThat(to.getValue()).isCloseTo(MedicationClock.now().toLocalTime(), within(2));
        assertThat(from.getValue()).isCloseTo(
                MedicationReminderPlanner.graceWindowStart(to.getValue(), properties.getGraceMinutes()), within(2));
    }

    // ─── 유예 창 경계(불변 규칙 ⑥) — 실 시각과 무관하게 검증 ────────────────

    @Test
    @DisplayName("[규칙⑥] 유예 창은 자정을 되감지 않는다 — 00:00에서 잘린다")
    void graceWindowStart_자정_절단() {
        // 되감으면 dose_date가 어제가 되어 "어제 약을 오늘 날짜로" 보내게 된다.
        assertThat(MedicationReminderPlanner.graceWindowStart(LocalTime.of(0, 0), 30)).isEqualTo(LocalTime.MIN);
        assertThat(MedicationReminderPlanner.graceWindowStart(LocalTime.of(0, 10), 30)).isEqualTo(LocalTime.MIN);
        // 경계 = 유예와 정확히 같은 시점까지 절단 대상
        assertThat(MedicationReminderPlanner.graceWindowStart(LocalTime.of(0, 30), 30)).isEqualTo(LocalTime.MIN);
    }

    @Test
    @DisplayName("[규칙⑥] 자정 경계를 벗어나면 현재-유예를 그대로 쓴다")
    void graceWindowStart_일반() {
        assertThat(MedicationReminderPlanner.graceWindowStart(LocalTime.of(0, 31), 30))
                .isEqualTo(LocalTime.of(0, 1));
        assertThat(MedicationReminderPlanner.graceWindowStart(LocalTime.of(12, 0), 30))
                .isEqualTo(LocalTime.of(11, 30));
        // 23:50 약이 자정을 넘겨 건너뛰어지는 것은 의도된 동작 — 하한이 되감기지 않는지만 본다.
        assertThat(MedicationReminderPlanner.graceWindowStart(LocalTime.of(23, 59), 30))
                .isEqualTo(LocalTime.of(23, 29));
    }

    // ─── 재알림 ────────────────────────────────────────────────────

    @Test
    @DisplayName("최초 알림 후에도 체크가 없으면 재알림을 선점한다")
    void claimRetry_대상선정() {
        OffsetDateTime firstSentAt = MedicationClock.now().minusMinutes(20);
        when(reminderLogRepository.findRetryCandidates(any(), any(), any()))
                .thenReturn(List.of(MedicationReminderLog.of(
                        1L, MedicationClock.today(), MedicationReminderLog.ATTEMPT_FIRST, firstSentAt)));
        when(intakeRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());
        when(medicationRepository.findAllById(any())).thenReturn(List.of(medication(1L, "혈압약")));
        when(settingService.findPreferences(any())).thenReturn(Map.of(WARD_ID, MedicationPreference.DEFAULT));

        List<MedicationReminderTarget> claimed = planner.claimRetryReminders();

        assertThat(claimed).singleElement()
                .satisfies(t -> assertThat(t.attempt()).isEqualTo(MedicationReminderLog.ATTEMPT_RETRY));
        verify(reminderLogRepository).saveAll(any());
    }

    @Test
    @DisplayName("재알림 조회 구간 = [현재-마감(60분), 현재-지연(15분)]")
    void claimRetry_조회구간() {
        when(reminderLogRepository.findRetryCandidates(any(), any(), any())).thenReturn(List.of());

        planner.claimRetryReminders();

        ArgumentCaptor<OffsetDateTime> from = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> to = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(reminderLogRepository).findRetryCandidates(eq(MedicationClock.today()), from.capture(), to.capture());

        long delayMinutes = java.time.Duration.between(to.getValue(), MedicationClock.now()).toMinutes();
        long deadlineMinutes = java.time.Duration.between(from.getValue(), MedicationClock.now()).toMinutes();
        assertThat(delayMinutes).isBetween(14L, 16L);
        assertThat(deadlineMinutes).isBetween(59L, 61L);
    }

    @Test
    @DisplayName("재알림을 끈 피보호자에게는 보내지 않는다(최초 알림은 이미 나간 뒤)")
    void claimRetry_재알림OFF_스킵() {
        when(reminderLogRepository.findRetryCandidates(any(), any(), any()))
                .thenReturn(List.of(MedicationReminderLog.of(1L, MedicationClock.today(),
                        MedicationReminderLog.ATTEMPT_FIRST, MedicationClock.now().minusMinutes(20))));
        when(intakeRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());
        when(medicationRepository.findAllById(any())).thenReturn(List.of(medication(1L, "혈압약")));
        when(settingService.findPreferences(any()))
                .thenReturn(Map.of(WARD_ID, new MedicationPreference(false)));

        assertThat(planner.claimRetryReminders()).isEmpty();
        verify(reminderLogRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("최초 알림 뒤에 복용 체크를 했으면 재알림하지 않는다")
    void claimRetry_중간에체크_스킵() {
        when(reminderLogRepository.findRetryCandidates(any(), any(), any()))
                .thenReturn(List.of(MedicationReminderLog.of(1L, MedicationClock.today(),
                        MedicationReminderLog.ATTEMPT_FIRST, MedicationClock.now().minusMinutes(20))));
        when(intakeRepository.findByMedicationIdInAndDoseDate(any(), any()))
                .thenReturn(List.of(MedicationIntake.of(1L, MedicationClock.today(), OffsetDateTime.now())));
        when(medicationRepository.findAllById(any())).thenReturn(List.of(medication(1L, "혈압약")));
        when(settingService.findPreferences(any())).thenReturn(Map.of(WARD_ID, MedicationPreference.DEFAULT));

        assertThat(planner.claimRetryReminders()).isEmpty();
    }

    @Test
    @DisplayName("최초 알림 뒤 삭제된 약은 재알림하지 않는다")
    void claimRetry_삭제된약_스킵() {
        Medication deleted = medication(1L, "혈압약");
        deleted.delete(OffsetDateTime.now());
        when(reminderLogRepository.findRetryCandidates(any(), any(), any()))
                .thenReturn(List.of(MedicationReminderLog.of(1L, MedicationClock.today(),
                        MedicationReminderLog.ATTEMPT_FIRST, MedicationClock.now().minusMinutes(20))));
        when(intakeRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());
        when(medicationRepository.findAllById(any())).thenReturn(List.of(deleted));
        when(settingService.findPreferences(any())).thenReturn(Map.of(WARD_ID, MedicationPreference.DEFAULT));

        assertThat(planner.claimRetryReminders()).isEmpty();
    }

    @Test
    @DisplayName("마감이 지연보다 짧게 설정되면 재알림 구간이 성립하지 않아 조회조차 하지 않는다")
    void claimRetry_설정역전_방어() {
        properties.setRetryDelayMinutes(60);
        properties.setRetryDeadlineMinutes(15);

        assertThat(planner.claimRetryReminders()).isEmpty();
        verify(reminderLogRepository, never()).findRetryCandidates(any(), any(), any());
    }

    // ─── ACTIVE 보호자 없음 (MED-G01) ─────────────────────────────────

    @Test
    @DisplayName("[MED-G01] ACTIVE 보호자가 한 명도 없는 피보호자의 약은 최초 알림 대상에서 빠진다 - 기록도 남기지 않는다")
    void claimFirst_보호자없음_제외() {
        when(medicationRepository.findByDeletedAtIsNullAndDoseTimeBetween(any(), any()))
                .thenReturn(List.of(medication(1L, "혈압약", UNGUARDED_WARD_ID)));
        when(intakeRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());
        when(reminderLogRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());
        when(settingService.findPreferences(any()))
                .thenReturn(Map.of(UNGUARDED_WARD_ID, MedicationPreference.DEFAULT));
        when(connectionRepository.findByParticipantsAndStatusIn(any(), any())).thenReturn(List.of());

        assertThat(planner.claimFirstReminders()).isEmpty();
        verify(reminderLogRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("[MED-G01] 보호자 있는 피보호자만 남기고, 연결은 ACTIVE 상태로 한 번에 조회한다(피보호자별 N+1 없음)")
    void claimFirst_보호자있는_피보호자만_일괄조회() {
        when(medicationRepository.findByDeletedAtIsNullAndDoseTimeBetween(any(), any()))
                .thenReturn(List.of(medication(1L, "혈압약", WARD_ID), medication(2L, "당뇨약", UNGUARDED_WARD_ID)));
        when(intakeRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());
        when(reminderLogRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());
        when(settingService.findPreferences(any())).thenReturn(Map.of(
                WARD_ID, MedicationPreference.DEFAULT, UNGUARDED_WARD_ID, MedicationPreference.DEFAULT));
        // 참여자 조회라 "그 ID가 보호자 쪽인 연결"이 섞여도 피보호자로 오인하지 않는다
        when(connectionRepository.findByParticipantsAndStatusIn(any(), any())).thenReturn(List.of(
                activeConnection("GD0001", WARD_ID),
                activeConnection(UNGUARDED_WARD_ID, "WD9999")));

        assertThat(planner.claimFirstReminders())
                .extracting(MedicationReminderTarget::medicationId)
                .containsExactly(1L);

        ArgumentCaptor<java.util.Collection<String>> wardIds = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(connectionRepository, times(1))
                .findByParticipantsAndStatusIn(wardIds.capture(), eq(List.of(ConnectionStatus.ACTIVE)));
        assertThat(wardIds.getValue()).containsExactlyInAnyOrder(WARD_ID, UNGUARDED_WARD_ID);
    }

    @Test
    @DisplayName("[MED-G01] 다른 조건에서 대상이 모두 빠지면 연결 조회도 하지 않는다")
    void claimFirst_대상없으면_연결조회없음() {
        when(medicationRepository.findByDeletedAtIsNullAndDoseTimeBetween(any(), any()))
                .thenReturn(List.of(medication(1L, "혈압약")));
        when(intakeRepository.findByMedicationIdInAndDoseDate(any(), any()))
                .thenReturn(List.of(MedicationIntake.of(1L, MedicationClock.today(), OffsetDateTime.now())));
        when(reminderLogRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());
        when(settingService.findPreferences(any())).thenReturn(Map.of(WARD_ID, MedicationPreference.DEFAULT));

        assertThat(planner.claimFirstReminders()).isEmpty();
        verify(connectionRepository, never()).findByParticipantsAndStatusIn(any(), any());
    }

    @Test
    @DisplayName("[MED-G01] 최초 알림 뒤 마지막 보호자와 연결이 끊겼으면 재알림도 보내지 않는다")
    void claimRetry_보호자없음_제외() {
        when(reminderLogRepository.findRetryCandidates(any(), any(), any()))
                .thenReturn(List.of(MedicationReminderLog.of(1L, MedicationClock.today(),
                        MedicationReminderLog.ATTEMPT_FIRST, MedicationClock.now().minusMinutes(20))));
        when(intakeRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());
        when(medicationRepository.findAllById(any())).thenReturn(List.of(medication(1L, "혈압약", UNGUARDED_WARD_ID)));
        when(settingService.findPreferences(any()))
                .thenReturn(Map.of(UNGUARDED_WARD_ID, MedicationPreference.DEFAULT));
        when(connectionRepository.findByParticipantsAndStatusIn(any(), any())).thenReturn(List.of());

        assertThat(planner.claimRetryReminders()).isEmpty();
        verify(reminderLogRepository, never()).saveAll(any());
    }

    // ─── 보호자 "복약 알림" 설정 (2026-11-04) ─────────────────────────

    private void dueFirst(Medication... medications) {
        when(medicationRepository.findByDeletedAtIsNullAndDoseTimeBetween(any(), any()))
                .thenReturn(List.of(medications));
        when(intakeRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());
        when(reminderLogRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("보호자 1명이 복약 알림을 켜 두면(행 없음=기본 켜짐) 보낸다")
    void gate_보호자1명_켜짐() {
        dueFirst(medication(1L, "혈압약"));

        assertThat(planner.claimFirstReminders()).hasSize(1);
    }

    @Test
    @DisplayName("보호자 1명이 계정에서 복약 알림을 끄면 보내지 않고 선점 기록도 만들지 않는다")
    void gate_계정토글_꺼짐() {
        dueFirst(medication(1L, "혈압약"));
        when(preferenceService.medicationDisabledGuardians()).thenReturn(Set.of("GD0001"));

        assertThat(planner.claimFirstReminders()).isEmpty();
        verify(reminderLogRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("보호자가 그 피보호자에 대해서만 복약 알림을 끄면 보내지 않는다(계정 AND 피보호자별)")
    void gate_피보호자별토글_꺼짐() {
        dueFirst(medication(1L, "혈압약"));
        when(guardianSettingRepository.findByWardIdInAndMissedAlertEnabledFalse(any()))
                .thenReturn(List.of(GuardianMedicationSetting.of("GD0001", WARD_ID, false)));

        assertThat(planner.claimFirstReminders()).isEmpty();
        verify(reminderLogRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("다른 피보호자에 대해 끈 행은 이 피보호자 판정에 섞이지 않는다")
    void gate_다른피보호자_끈행_무관() {
        dueFirst(medication(1L, "혈압약"));
        when(guardianSettingRepository.findByWardIdInAndMissedAlertEnabledFalse(any()))
                .thenReturn(List.of(GuardianMedicationSetting.of("GD0001", "WD7777", false)));

        assertThat(planner.claimFirstReminders()).hasSize(1);
    }

    @Test
    @DisplayName("보호자 2명 중 1명만 켜 두어도 보낸다")
    void gate_2명중1명_켜짐() {
        dueFirst(medication(1L, "혈압약"));
        when(connectionRepository.findByParticipantsAndStatusIn(any(), any())).thenReturn(List.of(
                activeConnection("GD0001", WARD_ID), activeConnection("GD0002", WARD_ID)));
        when(preferenceService.medicationDisabledGuardians()).thenReturn(Set.of("GD0001"));

        assertThat(planner.claimFirstReminders()).hasSize(1);
    }

    @Test
    @DisplayName("보호자 2명이 모두 끄면 보내지 않는다")
    void gate_2명모두_꺼짐() {
        dueFirst(medication(1L, "혈압약"));
        when(connectionRepository.findByParticipantsAndStatusIn(any(), any())).thenReturn(List.of(
                activeConnection("GD0001", WARD_ID), activeConnection("GD0002", WARD_ID)));
        when(preferenceService.medicationDisabledGuardians()).thenReturn(Set.of("GD0001"));
        when(guardianSettingRepository.findByWardIdInAndMissedAlertEnabledFalse(any()))
                .thenReturn(List.of(GuardianMedicationSetting.of("GD0002", WARD_ID, false)));

        assertThat(planner.claimFirstReminders()).isEmpty();
    }

    @Test
    @DisplayName("켜 둔 보호자가 이용 제한·탈퇴 진행 중이면 세지 않는다")
    void gate_정지보호자만_켜짐() {
        dueFirst(medication(1L, "혈압약"));
        when(connectionRepository.findByParticipantsAndStatusIn(any(), any())).thenReturn(List.of(
                activeConnection("GD0001", WARD_ID), activeConnection("GD0002", WARD_ID)));
        // 기본 스텁이 Answer라 when(...)으로 다시 걸면 null 인자로 그 Answer가 불린다
        doReturn(List.of(guardian("GD0001", Status.RESTRICTED), guardian("GD0002", Status.INACTIVE)))
                .when(userRepository).findAllById(any());

        assertThat(planner.claimFirstReminders()).isEmpty();
        verify(reminderLogRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("피보호자 둘 중 켠 보호자가 있는 쪽만 남고, 판정 조회는 피보호자 수와 무관하게 한 번씩이다(N+1 없음)")
    void gate_피보호자별_판정_일괄조회() {
        dueFirst(medication(1L, "혈압약", WARD_ID), medication(2L, "당뇨약", "WD0003"));
        when(connectionRepository.findByParticipantsAndStatusIn(any(), any())).thenReturn(List.of(
                activeConnection("GD0001", WARD_ID), activeConnection("GD0003", "WD0003")));
        when(preferenceService.medicationDisabledGuardians()).thenReturn(Set.of("GD0003"));

        assertThat(planner.claimFirstReminders())
                .extracting(MedicationReminderTarget::medicationId)
                .containsExactly(1L);
        verify(userRepository, times(1)).findAllById(any());
        verify(preferenceService, times(1)).medicationDisabledGuardians();
        verify(guardianSettingRepository, times(1)).findByWardIdInAndMissedAlertEnabledFalse(any());
    }

    @Test
    @DisplayName("최초 알림 뒤 보호자가 복약 알림을 끄면 재알림도 보내지 않는다")
    void gate_재알림_꺼짐() {
        when(reminderLogRepository.findRetryCandidates(any(), any(), any()))
                .thenReturn(List.of(MedicationReminderLog.of(1L, MedicationClock.today(),
                        MedicationReminderLog.ATTEMPT_FIRST, MedicationClock.now().minusMinutes(20))));
        when(intakeRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());
        when(medicationRepository.findAllById(any())).thenReturn(List.of(medication(1L, "혈압약")));
        when(settingService.findPreferences(any())).thenReturn(Map.of(WARD_ID, MedicationPreference.DEFAULT));
        when(preferenceService.medicationDisabledGuardians()).thenReturn(Set.of("GD0001"));

        assertThat(planner.claimRetryReminders()).isEmpty();
        verify(reminderLogRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("ACTIVE 연결이 없으면 보호자 설정 조회도 하지 않는다")
    void gate_보호자없음_설정조회없음() {
        dueFirst(medication(1L, "혈압약", UNGUARDED_WARD_ID));
        when(connectionRepository.findByParticipantsAndStatusIn(any(), any())).thenReturn(List.of());

        assertThat(planner.claimFirstReminders()).isEmpty();
        verify(userRepository, never()).findAllById(any());
        verify(preferenceService, never()).medicationDisabledGuardians();
    }

    private static User guardian(String id, Status status) {
        return User.builder().id(id).name("보호자").status(status).build();
    }

    private static Connection activeConnection(String guardianId, String wardId) {
        return Connection.builder()
                .guardianId(guardianId)
                .wardId(wardId)
                .status(ConnectionStatus.ACTIVE)
                .initiatedBy(guardianId)
                .build();
    }

    private static Medication medication(Long id, String name) {
        return medication(id, name, WARD_ID);
    }

    private static Medication medication(Long id, String name, String wardId) {
        Medication medication = Medication.builder()
                .wardId(wardId)
                .createdBy("GD0001")
                .name(name)
                .timeSlot(MedicationTimeSlot.MORNING)
                .doseTime(LocalTime.of(8, 0))
                .doseAmount(BigDecimal.valueOf(1))
                .build();
        ReflectionTestUtils.setField(medication, "id", id);
        return medication;
    }

    /** LocalTime 비교 오차 허용(테스트 실행 중 분이 바뀔 수 있음). */
    private static org.assertj.core.data.TemporalUnitOffset within(long minutes) {
        return new org.assertj.core.data.TemporalUnitLessThanOffset(minutes, java.time.temporal.ChronoUnit.MINUTES);
    }
}

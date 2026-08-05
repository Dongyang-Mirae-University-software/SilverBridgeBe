package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.medication.config.MedicationProperties;
import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.entity.MedicationIntake;
import kr.silverbridge.main.domain.medication.entity.MedicationReminderLog;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import kr.silverbridge.main.domain.medication.repository.MedicationIntakeRepository;
import kr.silverbridge.main.domain.medication.repository.MedicationReminderLogRepository;
import kr.silverbridge.main.domain.medication.repository.MedicationRepository;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MedicationReminderPlanner 단위 테스트 — <b>누구에게 보낼지 고르고, 두 번 보내지 않는지</b>가 핵심이다.
 *
 * <p>검증 축 — ① 미복용·설정 ON인 약만 대상 ② 이미 보낸 회차는 다시 보내지 않음(발송 기록 선점)
 * ③ 유예 창 경계 ④ 재알림 조건(지연·마감·재알림 설정·중간 체크).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // 분기별로 쓰이지 않는 스텁이 생긴다(조회 순서에 따라 조기 반환)
class MedicationReminderPlannerTest {

    @Mock private MedicationRepository medicationRepository;
    @Mock private MedicationIntakeRepository intakeRepository;
    @Mock private MedicationReminderLogRepository reminderLogRepository;
    @Mock private MedicationSettingService settingService;

    private MedicationProperties properties;
    private MedicationReminderPlanner planner;

    private static final String WARD_ID = "WD0001";

    @BeforeEach
    void setUp() {
        properties = new MedicationProperties();
        planner = new MedicationReminderPlanner(
                medicationRepository, intakeRepository, reminderLogRepository, settingService, properties);
    }

    // ─── 최초 발송 ──────────────────────────────────────────────────

    @Test
    @DisplayName("미복용 + 알림 ON인 약을 대상으로 잡고 발송 기록을 먼저 남긴다(선점)")
    void claimFirst_대상선정_선점() {
        Medication target = medication(1L, "혈압약");
        when(medicationRepository.findByDeletedAtIsNullAndDoseTimeBetween(any(), any()))
                .thenReturn(List.of(target));
        when(intakeRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());
        when(reminderLogRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());
        when(settingService.findPreferences(any())).thenReturn(Map.of(WARD_ID, MedicationPreference.DEFAULT));

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
        when(settingService.findPreferences(any())).thenReturn(Map.of(WARD_ID, MedicationPreference.DEFAULT));

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
        when(settingService.findPreferences(any())).thenReturn(Map.of(WARD_ID, MedicationPreference.DEFAULT));

        assertThat(planner.claimFirstReminders()).isEmpty();
        verify(reminderLogRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("알림 설정이 꺼진 피보호자에게는 보내지 않는다")
    void claimFirst_알림OFF_스킵() {
        when(medicationRepository.findByDeletedAtIsNullAndDoseTimeBetween(any(), any()))
                .thenReturn(List.of(medication(1L, "혈압약")));
        when(intakeRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());
        when(reminderLogRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());
        when(settingService.findPreferences(any()))
                .thenReturn(Map.of(WARD_ID, new MedicationPreference(false, true)));

        assertThat(planner.claimFirstReminders()).isEmpty();
    }

    @Test
    @DisplayName("유예 창 = [현재-30분, 현재] — 이 구간의 복용 시각만 조회한다")
    void claimFirst_유예창_조회구간() {
        when(medicationRepository.findByDeletedAtIsNullAndDoseTimeBetween(any(), any())).thenReturn(List.of());

        planner.claimFirstReminders();

        ArgumentCaptor<LocalTime> from = ArgumentCaptor.forClass(LocalTime.class);
        ArgumentCaptor<LocalTime> to = ArgumentCaptor.forClass(LocalTime.class);
        verify(medicationRepository).findByDeletedAtIsNullAndDoseTimeBetween(from.capture(), to.capture());

        LocalTime now = MedicationClock.now().toLocalTime();
        // 자정 직후에는 00:00에서 잘린다 — 하루를 되감아 어제 약을 오늘 날짜로 보내지 않기 위함
        LocalTime expectedFrom = now.toSecondOfDay() / 60 <= properties.getGraceMinutes()
                ? LocalTime.MIN
                : now.minusMinutes(properties.getGraceMinutes());
        assertThat(from.getValue()).isCloseTo(expectedFrom, within(2));
        assertThat(to.getValue()).isCloseTo(now, within(2));
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
                .thenReturn(Map.of(WARD_ID, new MedicationPreference(true, false)));

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

    private static Medication medication(Long id, String name) {
        Medication medication = Medication.builder()
                .wardId(WARD_ID)
                .createdBy("GD0001")
                .name(name)
                .timeSlot(MedicationTimeSlot.MORNING)
                .doseTime(LocalTime.of(8, 0))
                .doseAmount(1)
                .build();
        ReflectionTestUtils.setField(medication, "id", id);
        return medication;
    }

    /** LocalTime 비교 오차 허용(테스트 실행 중 분이 바뀔 수 있음). */
    private static org.assertj.core.data.TemporalUnitOffset within(long minutes) {
        return new org.assertj.core.data.TemporalUnitLessThanOffset(minutes, java.time.temporal.ChronoUnit.MINUTES);
    }
}

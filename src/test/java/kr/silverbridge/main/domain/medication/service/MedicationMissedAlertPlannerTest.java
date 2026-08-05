package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.medication.config.MedicationProperties;
import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.entity.MedicationIntake;
import kr.silverbridge.main.domain.medication.entity.MedicationMissedAlertLog;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import kr.silverbridge.main.domain.medication.repository.MedicationIntakeRepository;
import kr.silverbridge.main.domain.medication.repository.MedicationMissedAlertLogRepository;
import kr.silverbridge.main.domain.medication.repository.MedicationRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
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

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MedicationMissedAlertPlanner 단위 테스트.
 *
 * <p>검증 축 — ① 발송 창(판정 시각 전/마감 후) ② 미체크가 있는 피보호자만 대상 ③ 수신자 = ACTIVE 보호자
 * 중 설정 ON ④ 하루 한 번(이미 보낸 건 제외) ⑤ 집계 분모가 "판정 시각까지 예정된 약"이라는 점.</p>
 *
 * <p>발송 창 판정이 실제 시각(KST)에 의존하므로, 테스트는 <b>현재 시각을 판정 시각으로 설정</b>해
 * 항상 창 안이 되도록 만든다(고정 시계 주입 없이 결정적으로 만드는 방법).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MedicationMissedAlertPlannerTest {

    @Mock private MedicationRepository medicationRepository;
    @Mock private MedicationIntakeRepository intakeRepository;
    @Mock private MedicationMissedAlertLogRepository missedAlertLogRepository;
    @Mock private GuardianMedicationSettingService guardianSettingService;
    @Mock private ConnectionService connectionService;
    @Mock private UserRepository userRepository;

    private MedicationProperties properties;
    private MedicationMissedAlertPlanner planner;

    private static final String WARD_ID = "WD0001";
    private static final String GUARDIAN_A = "GD0001";
    private static final String GUARDIAN_B = "GD0002";

    @BeforeEach
    void setUp() {
        properties = new MedicationProperties();
        // 지금이 판정 시각이 되도록 맞춘다 → 발송 창 안(마감 120분)
        properties.getMissedAlert().setAlertTime(MedicationClock.now().toLocalTime());
        planner = new MedicationMissedAlertPlanner(
                medicationRepository, intakeRepository, missedAlertLogRepository,
                guardianSettingService, connectionService, userRepository, properties);
    }

    @Test
    @DisplayName("미체크가 있는 피보호자의 ACTIVE 보호자 전원에게 보낼 요약을 선점한다")
    void 대상선정_선점() {
        // 예정 2건 중 1건만 복용 체크됨
        when(medicationRepository.findByDeletedAtIsNullAndDoseTimeLessThanEqual(any()))
                .thenReturn(List.of(medication(1L, "혈압약"), medication(2L, "당뇨약")));
        when(intakeRepository.findByMedicationIdInAndDoseDate(any(), any()))
                .thenReturn(List.of(MedicationIntake.of(1L, MedicationClock.today(), OffsetDateTime.now())));
        when(missedAlertLogRepository.findByDoseDateAndWardIdIn(any(), any())).thenReturn(List.of());
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_A, GUARDIAN_B));
        when(guardianSettingService.findMissedAlertEnabled(any()))
                .thenReturn(Map.of(GUARDIAN_A, true, GUARDIAN_B, true));
        when(userRepository.findAllById(any()))
                .thenReturn(List.of(User.builder().id(WARD_ID).name("김영희").build()));

        List<MedicationMissedAlertTarget> claimed = planner.claimMissedAlerts();

        assertThat(claimed).hasSize(2)
                .allSatisfy(t -> {
                    assertThat(t.wardName()).isEqualTo("김영희");
                    assertThat(t.totalCount()).isEqualTo(2);   // 판정 시각까지 예정된 약
                    assertThat(t.missedCount()).isEqualTo(1);  // 그중 미체크
                })
                .extracting(MedicationMissedAlertTarget::guardianId)
                .containsExactlyInAnyOrder(GUARDIAN_A, GUARDIAN_B);

        ArgumentCaptor<List<MedicationMissedAlertLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(missedAlertLogRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("전부 복용 체크됐으면 아무에게도 보내지 않는다")
    void 전부체크_미발송() {
        when(medicationRepository.findByDeletedAtIsNullAndDoseTimeLessThanEqual(any()))
                .thenReturn(List.of(medication(1L, "혈압약")));
        when(intakeRepository.findByMedicationIdInAndDoseDate(any(), any()))
                .thenReturn(List.of(MedicationIntake.of(1L, MedicationClock.today(), OffsetDateTime.now())));

        assertThat(planner.claimMissedAlerts()).isEmpty();
        verify(missedAlertLogRepository, never()).saveAll(any());
        verify(connectionService, never()).getActiveGuardianIds(any());
    }

    @Test
    @DisplayName("판정 시각 전에는 조회조차 하지 않는다")
    void 판정시각_전() {
        properties.getMissedAlert().setAlertTime(MedicationClock.now().toLocalTime().plusHours(1));

        assertThat(planner.claimMissedAlerts()).isEmpty();
        verify(medicationRepository, never()).findByDeletedAtIsNullAndDoseTimeLessThanEqual(any());
    }

    @Test
    @DisplayName("마감이 지나면 그날은 보내지 않는다(늦게 복구된 서버가 자정 직전에 보내는 것 방지)")
    void 마감_경과() {
        properties.getMissedAlert().setAlertTime(MedicationClock.now().toLocalTime().minusMinutes(90));
        properties.getMissedAlert().setDeadlineMinutes(30);

        assertThat(planner.claimMissedAlerts()).isEmpty();
        verify(medicationRepository, never()).findByDeletedAtIsNullAndDoseTimeLessThanEqual(any());
    }

    @Test
    @DisplayName("수신 설정을 끈 보호자는 제외한다")
    void 설정OFF_제외() {
        stubOneMissed();
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_A, GUARDIAN_B));
        when(guardianSettingService.findMissedAlertEnabled(any()))
                .thenReturn(Map.of(GUARDIAN_A, false, GUARDIAN_B, true));

        assertThat(planner.claimMissedAlerts())
                .extracting(MedicationMissedAlertTarget::guardianId)
                .containsExactly(GUARDIAN_B);
    }

    @Test
    @DisplayName("설정 행이 없는 보호자는 기본값(ON)으로 받는다")
    void 설정없음_기본ON() {
        stubOneMissed();
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_A));
        when(guardianSettingService.findMissedAlertEnabled(any())).thenReturn(Map.of());

        assertThat(planner.claimMissedAlerts()).hasSize(1);
    }

    @Test
    @DisplayName("오늘 이미 보낸 보호자에게는 다시 보내지 않는다 — 하루 한 번")
    void 이미발송_제외() {
        stubOneMissed();
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_A, GUARDIAN_B));
        when(guardianSettingService.findMissedAlertEnabled(any()))
                .thenReturn(Map.of(GUARDIAN_A, true, GUARDIAN_B, true));
        when(missedAlertLogRepository.findByDoseDateAndWardIdIn(any(), any()))
                .thenReturn(List.of(MedicationMissedAlertLog.of(
                        GUARDIAN_A, WARD_ID, MedicationClock.today(), 1, 1, OffsetDateTime.now())));

        assertThat(planner.claimMissedAlerts())
                .extracting(MedicationMissedAlertTarget::guardianId)
                .containsExactly(GUARDIAN_B);
    }

    @Test
    @DisplayName("연결된 보호자가 없으면 발송 기록도 남기지 않는다")
    void 보호자없음_미발송() {
        stubOneMissed();
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of());

        assertThat(planner.claimMissedAlerts()).isEmpty();
        verify(missedAlertLogRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("집계 상한은 판정 시각 — 그 이후에 먹는 약(취침 전 22:00)은 조회에서 빠진다")
    void 집계상한_판정시각() {
        // setUp에서 판정 시각 = 현재 시각으로 맞춰 창 안이 보장된 상태
        LocalTime alertTime = properties.getMissedAlert().getAlertTime();
        when(medicationRepository.findByDeletedAtIsNullAndDoseTimeLessThanEqual(any())).thenReturn(List.of());

        planner.claimMissedAlerts();

        ArgumentCaptor<LocalTime> captor = ArgumentCaptor.forClass(LocalTime.class);
        verify(medicationRepository).findByDeletedAtIsNullAndDoseTimeLessThanEqual(captor.capture());
        assertThat(captor.getValue()).isEqualTo(alertTime);
    }

    /** 예정 1건 · 전부 미체크 · 이름 조회까지 스텁. */
    private void stubOneMissed() {
        when(medicationRepository.findByDeletedAtIsNullAndDoseTimeLessThanEqual(any()))
                .thenReturn(List.of(medication(1L, "혈압약")));
        when(intakeRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());
        when(missedAlertLogRepository.findByDoseDateAndWardIdIn(any(), any())).thenReturn(List.of());
        when(userRepository.findAllById(any()))
                .thenReturn(List.of(User.builder().id(WARD_ID).name("김영희").build()));
    }

    private static Medication medication(Long id, String name) {
        Medication medication = Medication.builder()
                .wardId(WARD_ID)
                .createdBy(GUARDIAN_A)
                .name(name)
                .timeSlot(MedicationTimeSlot.MORNING)
                .doseTime(LocalTime.of(8, 0))
                .doseAmount(1)
                .build();
        ReflectionTestUtils.setField(medication, "id", id);
        return medication;
    }
}

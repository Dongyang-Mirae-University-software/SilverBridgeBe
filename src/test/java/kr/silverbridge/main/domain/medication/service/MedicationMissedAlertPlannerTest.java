package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.medication.config.MedicationProperties;
import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.entity.MedicationIntake;
import kr.silverbridge.main.domain.medication.entity.MedicationMissedAlertLog;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import kr.silverbridge.main.domain.medication.repository.GuardianMedicationSettingRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MedicationMissedAlertPlanner 단위 테스트.
 *
 * <p>검증 축 - ① 발송 창(지정 시각 전/마감 후) ② 미체크가 있는 피보호자만 대상 ③ 수신자 = ACTIVE 보호자
 * 중 설정 ON ④ 하루 한 번(이미 보낸 건 제외) ⑤ <b>집계 분모가 보호자마다 다르다</b>는 점
 * (2026-08-27부터 발송 시각이 보호자별 설정이라, 같은 피보호자라도 시각이 이른 보호자는 분모가 작다).</p>
 *
 * <p>발송 창 판정이 실제 시각(KST)에 의존하므로, 테스트는 <b>현재 시각을 기준으로 상대 시각</b>을 잡아
 * 항상 창 안/밖이 되도록 만든다(고정 시계 주입 없이 결정적으로 만드는 방법).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MedicationMissedAlertPlannerTest {

    @Mock private MedicationRepository medicationRepository;
    @Mock private MedicationIntakeRepository intakeRepository;
    @Mock private MedicationMissedAlertLogRepository missedAlertLogRepository;
    @Mock private GuardianMedicationSettingRepository guardianSettingRepository;
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
        // 지금이 기본 발송 시각이 되도록 맞춘다 → 발송 창 안(마감 120분)
        setDefaultAlertTime(MedicationClock.now().toLocalTime());
        planner = new MedicationMissedAlertPlanner(
                medicationRepository, intakeRepository, missedAlertLogRepository,
                guardianSettingRepository, guardianSettingService, connectionService, userRepository, properties);
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
        when(guardianSettingService.findSettings(any(), any())).thenReturn(Map.of());
        when(userRepository.findAllById(any()))
                .thenReturn(List.of(User.builder().id(WARD_ID).name("김영희").build()));

        List<MedicationMissedAlertTarget> claimed = planner.claimMissedAlerts();

        assertThat(claimed).hasSize(2)
                .allSatisfy(t -> {
                    assertThat(t.wardName()).isEqualTo("김영희");
                    assertThat(t.totalCount()).isEqualTo(2);   // 집계 상한까지 예정된 약
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
    @DisplayName("아무도 받을 시각이 아니면 복약 테이블을 조회조차 하지 않는다")
    void 발송시각_전() {
        setDefaultAlertTime(MedicationClock.now().toLocalTime().plusHours(1));

        assertThat(planner.claimMissedAlerts()).isEmpty();
        verify(medicationRepository, never()).findByDeletedAtIsNullAndDoseTimeLessThanEqual(any());
    }

    @Test
    @DisplayName("이른 시각을 지정한 보호자가 있으면 기본 시각 전이라도 조회한다")
    void 이른시각_보호자_게이트통과() {
        LocalTime now = MedicationClock.now().toLocalTime();
        setDefaultAlertTime(now.plusHours(1));                       // 기본값만 보면 아직 발송 시각 전
        LocalTime early = now.minusMinutes(10);
        when(guardianSettingRepository.findEarliestAlertTime()).thenReturn(Optional.of(early));
        when(guardianSettingRepository.findLatestAlertTime()).thenReturn(Optional.of(early));
        stubOneMissed();
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_A));
        when(guardianSettingService.findSettings(any(), any()))
                .thenReturn(Map.of(GUARDIAN_A, new GuardianMissedAlertSetting(true, early)));

        assertThat(planner.claimMissedAlerts()).hasSize(1);
        verify(medicationRepository).findByDeletedAtIsNullAndDoseTimeLessThanEqual(any());
    }

    @Test
    @DisplayName("마감이 지나면 그날은 보내지 않는다(늦게 복구된 서버가 자정 직전에 보내는 것 방지)")
    void 마감_경과() {
        setDefaultAlertTime(MedicationClock.now().toLocalTime().minusMinutes(90));
        properties.getMissedAlert().setDeadlineMinutes(30);

        assertThat(planner.claimMissedAlerts()).isEmpty();
        verify(medicationRepository, never()).findByDeletedAtIsNullAndDoseTimeLessThanEqual(any());
    }

    @Test
    @DisplayName("수신 설정을 끈 보호자는 제외한다")
    void 설정OFF_제외() {
        LocalTime alertTime = properties.getMissedAlert().getAlertTime();
        stubOneMissed();
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_A, GUARDIAN_B));
        when(guardianSettingService.findSettings(any(), any())).thenReturn(Map.of(
                GUARDIAN_A, new GuardianMissedAlertSetting(false, alertTime),
                GUARDIAN_B, new GuardianMissedAlertSetting(true, alertTime)));

        assertThat(planner.claimMissedAlerts())
                .extracting(MedicationMissedAlertTarget::guardianId)
                .containsExactly(GUARDIAN_B);
    }

    @Test
    @DisplayName("설정 행이 없는 보호자는 기본값(ON · 기본 시각)으로 받는다")
    void 설정없음_기본값() {
        stubOneMissed();
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_A));
        when(guardianSettingService.findSettings(any(), any())).thenReturn(Map.of());

        assertThat(planner.claimMissedAlerts())
                .singleElement()
                .satisfies(t -> assertThat(t.alertTime()).isEqualTo(properties.getMissedAlert().getAlertTime()));
    }

    @Test
    @DisplayName("아직 지정 시각이 안 된 보호자는 이번 주기에서 빠진다")
    void 보호자시각_전_제외() {
        stubOneMissed();
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_A));
        when(guardianSettingService.findSettings(any(), any())).thenReturn(Map.of(
                GUARDIAN_A, new GuardianMissedAlertSetting(true, MedicationClock.now().toLocalTime().plusMinutes(30))));

        assertThat(planner.claimMissedAlerts()).isEmpty();
        verify(missedAlertLogRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("오늘 이미 보낸 보호자에게는 다시 보내지 않는다 - 하루 한 번")
    void 이미발송_제외() {
        LocalTime alertTime = properties.getMissedAlert().getAlertTime();
        stubOneMissed();
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_A, GUARDIAN_B));
        when(guardianSettingService.findSettings(any(), any())).thenReturn(Map.of(
                GUARDIAN_A, new GuardianMissedAlertSetting(true, alertTime),
                GUARDIAN_B, new GuardianMissedAlertSetting(true, alertTime)));
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
    @DisplayName("조회는 현재 시각까지의 상위집합으로 한 번만 - 보호자별 상한은 그 안에서 다시 거른다")
    void 조회는_현재시각까지() {
        when(medicationRepository.findByDeletedAtIsNullAndDoseTimeLessThanEqual(any())).thenReturn(List.of());
        LocalTime before = MedicationClock.now().toLocalTime();

        planner.claimMissedAlerts();

        LocalTime after = MedicationClock.now().toLocalTime();
        ArgumentCaptor<LocalTime> captor = ArgumentCaptor.forClass(LocalTime.class);
        verify(medicationRepository).findByDeletedAtIsNullAndDoseTimeLessThanEqual(captor.capture());
        // 지정 시각이 아니라 "지금"까지 - 시각이 다른 보호자들의 집계 대상을 모두 덮어야 한다
        assertThat(captor.getValue()).isBetween(before, after);
    }

    @Test
    @DisplayName("집계 상한은 보호자가 지정한 시각 - 같은 피보호자라도 이른 시각을 고른 보호자는 분모가 작다")
    void 집계상한_보호자별() {
        LocalTime now = MedicationClock.now().toLocalTime();
        LocalTime earlyCutoff = now.minusMinutes(60);   // A가 지정한 시각(창 안)
        LocalTime lateCutoff = now;                     // B가 지정한 시각(창 안)

        // 아침 약은 두 상한 모두에 걸리고, 늦은 약은 B의 상한에만 걸린다. 둘 다 미체크.
        when(medicationRepository.findByDeletedAtIsNullAndDoseTimeLessThanEqual(any())).thenReturn(List.of(
                medication(1L, "혈압약", now.minusMinutes(90)),
                medication(2L, "저녁약", now.minusMinutes(30))));
        when(intakeRepository.findByMedicationIdInAndDoseDate(any(), any())).thenReturn(List.of());
        when(missedAlertLogRepository.findByDoseDateAndWardIdIn(any(), any())).thenReturn(List.of());
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_A, GUARDIAN_B));
        when(guardianSettingService.findSettings(any(), any())).thenReturn(Map.of(
                GUARDIAN_A, new GuardianMissedAlertSetting(true, earlyCutoff),
                GUARDIAN_B, new GuardianMissedAlertSetting(true, lateCutoff)));
        when(userRepository.findAllById(any()))
                .thenReturn(List.of(User.builder().id(WARD_ID).name("김영희").build()));

        List<MedicationMissedAlertTarget> claimed = planner.claimMissedAlerts();

        assertThat(claimed).hasSize(2);
        assertThat(claimed).filteredOn(t -> t.guardianId().equals(GUARDIAN_A)).singleElement()
                .satisfies(t -> {
                    assertThat(t.totalCount()).isEqualTo(1);   // 늦은 약은 아직 먹을 때가 아니라 제외
                    assertThat(t.missedCount()).isEqualTo(1);
                    assertThat(t.alertTime()).isEqualTo(earlyCutoff);
                });
        assertThat(claimed).filteredOn(t -> t.guardianId().equals(GUARDIAN_B)).singleElement()
                .satisfies(t -> {
                    assertThat(t.totalCount()).isEqualTo(2);
                    assertThat(t.missedCount()).isEqualTo(2);
                    assertThat(t.alertTime()).isEqualTo(lateCutoff);
                });
    }

    @Test
    @DisplayName("설정은 그 피보호자 축으로 조회한다 - 다른 피보호자에 지정한 시각이 섞이면 안 된다")
    void 설정조회는_피보호자축() {
        stubOneMissed();
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_A));
        when(guardianSettingService.findSettings(any(), any())).thenReturn(Map.of());

        planner.claimMissedAlerts();

        verify(guardianSettingService).findSettings(eq(WARD_ID), eq(List.of(GUARDIAN_A)));
    }

    /** 기본 시각을 바꾸고, 그에 맞춰 서비스가 돌려줄 기본 실효값도 갱신한다. */
    private void setDefaultAlertTime(LocalTime alertTime) {
        properties.getMissedAlert().setAlertTime(alertTime);
        GuardianMissedAlertSetting defaultSetting = new GuardianMissedAlertSetting(true, alertTime);
        when(guardianSettingService.defaultSetting()).thenReturn(defaultSetting);
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
        return medication(id, name, LocalTime.of(0, 0));
    }

    private static Medication medication(Long id, String name, LocalTime doseTime) {
        Medication medication = Medication.builder()
                .wardId(WARD_ID)
                .createdBy(GUARDIAN_A)
                .name(name)
                .timeSlot(MedicationTimeSlot.MORNING)
                .doseTime(doseTime)
                .doseAmount(1)
                .build();
        ReflectionTestUtils.setField(medication, "id", id);
        return medication;
    }
}

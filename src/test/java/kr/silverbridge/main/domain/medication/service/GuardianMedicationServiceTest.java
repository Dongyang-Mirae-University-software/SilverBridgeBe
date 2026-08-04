package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.medication.dto.MedicationCreateRequest;
import kr.silverbridge.main.domain.medication.dto.MedicationItem;
import kr.silverbridge.main.domain.medication.dto.WardMedicationSummary;
import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.entity.MedicationIntake;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import kr.silverbridge.main.domain.medication.repository.MedicationIntakeRepository;
import kr.silverbridge.main.domain.medication.repository.MedicationRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * GuardianMedicationService 단위 테스트.
 *
 * <p>핵심은 <b>인가</b>다 — 보호자는 ACTIVE 연결된 피보호자의 복약 정보만 보고 관리할 수 있어야 한다.
 * 그 밖에 ① 피보호자별 카드 조립(약 없는 피보호자 포함) ② 복용 카운트 정확성 ③ 만 나이 계산
 * ④ 알림 설정 기본값 ⑤ soft delete 동작을 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class GuardianMedicationServiceTest {

    @Mock private MedicationRepository medicationRepository;
    @Mock private MedicationIntakeRepository intakeRepository;
    @Mock private MedicationSettingService settingService;
    @Mock private ConnectionService connectionService;
    @Mock private UserRepository userRepository;

    @InjectMocks private GuardianMedicationService guardianMedicationService;

    private static final String GUARDIAN_ID = "GD0001";
    private static final String WARD_ID = "WD0001";
    private static final String OTHER_WARD_ID = "WD0002";

    // ─── 피보호자별 현황 조회 ────────────────────────────────────────

    @Test
    @DisplayName("ACTIVE 연결된 피보호자 전원의 카드를 조립하고 오늘 복용 카운트를 계산한다")
    void getWardMedications_카드조립_카운트() {
        LocalDate today = MedicationClock.today();
        Medication morning = medication(1L, WARD_ID, "혈압약", MedicationTimeSlot.MORNING, LocalTime.of(8, 0));
        Medication lunch = medication(2L, WARD_ID, "당뇨약", MedicationTimeSlot.LUNCH, LocalTime.of(13, 0));
        Medication bedtime = medication(3L, WARD_ID, "수면 보조제", MedicationTimeSlot.BEDTIME, LocalTime.of(22, 0));

        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of(WARD_ID, OTHER_WARD_ID));
        when(medicationRepository.findByWardIdInAndDeletedAtIsNullOrderByDoseTimeAscIdAsc(any()))
                .thenReturn(List.of(morning, lunch, bedtime));
        when(intakeRepository.findByMedicationIdInAndDoseDate(any(), eq(today)))
                .thenReturn(List.of(
                        MedicationIntake.of(1L, today, OffsetDateTime.now()),
                        MedicationIntake.of(2L, today, OffsetDateTime.now())));
        when(userRepository.findAllById(any())).thenReturn(List.of(user(WARD_ID, "김영희", LocalDate.of(1948, 3, 2))));
        when(settingService.findAlarmEnabledByWardIds(any())).thenReturn(Map.of(WARD_ID, false));

        List<WardMedicationSummary> summaries = guardianMedicationService.getWardMedications(GUARDIAN_ID);

        assertThat(summaries).hasSize(2);

        WardMedicationSummary first = summaries.get(0);
        assertThat(first.wardId()).isEqualTo(WARD_ID);
        assertThat(first.wardName()).isEqualTo("김영희");
        assertThat(first.age()).isEqualTo(Period.between(LocalDate.of(1948, 3, 2), today).getYears());
        assertThat(first.alarmEnabled()).isFalse();
        assertThat(first.doseDate()).isEqualTo(today);
        assertThat(first.takenCount()).isEqualTo(2);
        assertThat(first.totalCount()).isEqualTo(3);
        assertThat(first.medications()).extracting(MedicationItem::name)
                .containsExactly("혈압약", "당뇨약", "수면 보조제");
        assertThat(first.medications()).extracting(MedicationItem::taken)
                .containsExactly(true, true, false);
    }

    @Test
    @DisplayName("약이 없는 피보호자도 카드는 나온다(0/0) — 설정 행이 없으면 알림 기본값 ON")
    void getWardMedications_약없는_피보호자도_카드포함() {
        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of(OTHER_WARD_ID));
        when(medicationRepository.findByWardIdInAndDeletedAtIsNullOrderByDoseTimeAscIdAsc(any()))
                .thenReturn(List.of());
        when(userRepository.findAllById(any())).thenReturn(List.of(user(OTHER_WARD_ID, "이순자", null)));
        when(settingService.findAlarmEnabledByWardIds(any())).thenReturn(Map.of());

        List<WardMedicationSummary> summaries = guardianMedicationService.getWardMedications(GUARDIAN_ID);

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).medications()).isEmpty();
        assertThat(summaries.get(0).takenCount()).isZero();
        assertThat(summaries.get(0).totalCount()).isZero();
        assertThat(summaries.get(0).alarmEnabled()).isTrue();
        // 생년월일이 없으면 나이는 null — 프론트가 표기를 생략한다.
        assertThat(summaries.get(0).age()).isNull();
        // 약이 없으면 복용 체크를 조회할 필요도 없다.
        verify(intakeRepository, never()).findByMedicationIdInAndDoseDate(any(), any());
    }

    @Test
    @DisplayName("연결된 피보호자가 없으면 빈 목록 — 약 조회 자체를 하지 않는다")
    void getWardMedications_연결없음_빈목록() {
        when(connectionService.getActiveWardIds(GUARDIAN_ID)).thenReturn(List.of());

        assertThat(guardianMedicationService.getWardMedications(GUARDIAN_ID)).isEmpty();

        verify(medicationRepository, never()).findByWardIdInAndDeletedAtIsNullOrderByDoseTimeAscIdAsc(any());
    }

    // ─── 약 등록 ────────────────────────────────────────────────────

    @Test
    @DisplayName("약 등록 — 복용 시각을 생략하면 시간대 기본값(취침 전 22:00)으로 저장된다")
    void create_시각생략_기본값적용() {
        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(true);
        when(medicationRepository.save(any(Medication.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MedicationItem created = guardianMedicationService.create(GUARDIAN_ID, WARD_ID,
                new MedicationCreateRequest("수면 보조제", MedicationTimeSlot.BEDTIME, null, null, null));

        ArgumentCaptor<Medication> captor = ArgumentCaptor.forClass(Medication.class);
        verify(medicationRepository).save(captor.capture());
        Medication saved = captor.getValue();
        assertThat(saved.getWardId()).isEqualTo(WARD_ID);
        assertThat(saved.getCreatedBy()).isEqualTo(GUARDIAN_ID);
        assertThat(saved.getDoseTime()).isEqualTo(LocalTime.of(22, 0));
        assertThat(saved.getDoseAmount()).isEqualTo(1);
        // 방금 등록한 약은 오늘 아직 복용 전이다.
        assertThat(created.taken()).isFalse();
        assertThat(created.takenAt()).isNull();
    }

    @Test
    @DisplayName("[IDOR] 연결되지 않은 피보호자에게 약 등록 시도 → 403, 저장하지 않는다")
    void create_연결없음_차단() {
        when(connectionService.isActiveConnection(GUARDIAN_ID, OTHER_WARD_ID)).thenReturn(false);

        assertThatThrownBy(() -> guardianMedicationService.create(GUARDIAN_ID, OTHER_WARD_ID,
                new MedicationCreateRequest("혈압약", MedicationTimeSlot.MORNING, null, 1, null)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEDICATION_NOT_AUTHORIZED);

        verify(medicationRepository, never()).save(any());
    }

    // ─── 약 삭제 ────────────────────────────────────────────────────

    @Test
    @DisplayName("약 삭제 — soft delete로 deletedAt만 채운다(복용 이력 보존)")
    void delete_softDelete() {
        Medication medication = medication(1L, WARD_ID, "혈압약", MedicationTimeSlot.MORNING, LocalTime.of(8, 0));
        when(medicationRepository.findById(1L)).thenReturn(Optional.of(medication));
        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(true);

        guardianMedicationService.delete(GUARDIAN_ID, 1L);

        assertThat(medication.isDeleted()).isTrue();
        verify(medicationRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[IDOR] 연결되지 않은 피보호자의 약 삭제 시도 → 403, 삭제되지 않는다")
    void delete_연결없음_차단() {
        Medication medication = medication(1L, OTHER_WARD_ID, "혈압약", MedicationTimeSlot.MORNING, LocalTime.of(8, 0));
        when(medicationRepository.findById(1L)).thenReturn(Optional.of(medication));
        when(connectionService.isActiveConnection(GUARDIAN_ID, OTHER_WARD_ID)).thenReturn(false);

        assertThatThrownBy(() -> guardianMedicationService.delete(GUARDIAN_ID, 1L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEDICATION_NOT_AUTHORIZED);

        assertThat(medication.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("이미 삭제된 약은 없는 것으로 취급 → 404 (연결 확인 전에 막힌다)")
    void delete_이미삭제_404() {
        Medication medication = medication(1L, WARD_ID, "혈압약", MedicationTimeSlot.MORNING, LocalTime.of(8, 0));
        medication.delete(OffsetDateTime.now());
        when(medicationRepository.findById(1L)).thenReturn(Optional.of(medication));

        assertThatThrownBy(() -> guardianMedicationService.delete(GUARDIAN_ID, 1L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEDICATION_NOT_FOUND);

        verify(connectionService, never()).isActiveConnection(anyString(), anyString());
    }

    @Test
    @DisplayName("존재하지 않는 약 삭제 → 404")
    void delete_없는약_404() {
        when(medicationRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guardianMedicationService.delete(GUARDIAN_ID, 99L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEDICATION_NOT_FOUND);
    }

    // ─── 알림 설정 ──────────────────────────────────────────────────

    @Test
    @DisplayName("[IDOR] 연결되지 않은 피보호자의 알림 설정 변경 시도 → 403, 저장하지 않는다")
    void updateSetting_연결없음_차단() {
        when(connectionService.isActiveConnection(GUARDIAN_ID, OTHER_WARD_ID)).thenReturn(false);

        assertThatThrownBy(() -> guardianMedicationService.updateSetting(GUARDIAN_ID, OTHER_WARD_ID, false))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEDICATION_NOT_AUTHORIZED);

        verify(settingService, never()).updateAlarmEnabled(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("알림 설정 변경 — 피보호자 계정에 저장된다")
    void updateSetting_정상() {
        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(true);
        when(settingService.updateAlarmEnabled(WARD_ID, false)).thenReturn(false);

        assertThat(guardianMedicationService.updateSetting(GUARDIAN_ID, WARD_ID, false))
                .satisfies(response -> {
                    assertThat(response.wardId()).isEqualTo(WARD_ID);
                    assertThat(response.alarmEnabled()).isFalse();
                });
    }

    // ─── 헬퍼 ───────────────────────────────────────────────────────

    private static Medication medication(Long id, String wardId, String name,
                                         MedicationTimeSlot slot, LocalTime doseTime) {
        Medication medication = Medication.builder()
                .wardId(wardId)
                .createdBy(GUARDIAN_ID)
                .name(name)
                .timeSlot(slot)
                .doseTime(doseTime)
                .doseAmount(1)
                .build();
        ReflectionTestUtils.setField(medication, "id", id);
        return medication;
    }

    private static User user(String id, String name, LocalDate birthDate) {
        return User.builder().id(id).name(name).birthDate(birthDate).build();
    }
}

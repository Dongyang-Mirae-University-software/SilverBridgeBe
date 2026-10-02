package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.medication.dto.MedicationItem;
import kr.silverbridge.main.domain.medication.dto.MedicationUpdateRequest;
import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.entity.MedicationIntake;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import kr.silverbridge.main.domain.medication.repository.MedicationIntakeRepository;
import kr.silverbridge.main.domain.medication.repository.MedicationReminderLogRepository;
import kr.silverbridge.main.domain.medication.repository.MedicationRepository;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 약 수정(PATCH) 단위 테스트.
 *
 * <p>검증 축 — ① 부분 수정(보낸 필드만 변경) ② <b>시간대만 바꿨을 때 시각 자동 조정</b>
 * ③ <b>시각이 바뀌면 당일 발송 기록 초기화</b>(안 바뀌면 그대로) ④ 메모 삭제 규약
 * ⑤ 인가·404 ⑥ 복용 체크가 수정으로 지워지지 않는지.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GuardianMedicationUpdateTest {

    @Mock private MedicationRepository medicationRepository;
    @Mock private MedicationIntakeRepository intakeRepository;
    @Mock private MedicationReminderLogRepository reminderLogRepository;
    @Mock private MedicationSettingService settingService;
    @Mock private ConnectionService connectionService;
    @Mock private UserRepository userRepository;

    @InjectMocks private GuardianMedicationService guardianMedicationService;

    private static final String GUARDIAN_ID = "GD0001";
    private static final String WARD_ID = "WD0001";
    private static final String OTHER_WARD_ID = "WD0002";

    // ─── 부분 수정 ──────────────────────────────────────────────────

    @Test
    @DisplayName("이름만 보내면 이름만 바뀌고 나머지는 그대로 — 발송 기록도 건드리지 않는다")
    void 이름만_수정() {
        Medication medication = givenConnectedMedication();

        MedicationItem item = guardianMedicationService.update(GUARDIAN_ID, 1L,
                new MedicationUpdateRequest("혈압약(변경)", null, null, null, null));

        assertThat(medication.getName()).isEqualTo("혈압약(변경)");
        assertThat(medication.getTimeSlot()).isEqualTo(MedicationTimeSlot.MORNING);
        assertThat(medication.getDoseTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(medication.getDoseAmount()).isEqualTo(1);
        assertThat(medication.getMemo()).isEqualTo("식후 30분");
        assertThat(item.name()).isEqualTo("혈압약(변경)");
        // 시각이 그대로면 오늘 발송 기록을 지우지 않는다(중복 알림 방지)
        verify(reminderLogRepository, never()).deleteByMedicationIdAndDoseDate(anyLong(), any());
    }

    @Test
    @DisplayName("아무 필드도 보내지 않으면 아무것도 바뀌지 않는다")
    void 빈_요청() {
        Medication medication = givenConnectedMedication();

        guardianMedicationService.update(GUARDIAN_ID, 1L,
                new MedicationUpdateRequest(null, null, null, null, null));

        assertThat(medication.getName()).isEqualTo("혈압약");
        assertThat(medication.getDoseTime()).isEqualTo(LocalTime.of(8, 0));
        verify(reminderLogRepository, never()).deleteByMedicationIdAndDoseDate(anyLong(), any());
    }

    // ─── 시간대·시각 ────────────────────────────────────────────────

    @Test
    @DisplayName("시간대만 바꾸면 새 시간대의 기본 시각으로 갱신된다 — '저녁 08:00'이 남지 않게")
    void 시간대만_변경() {
        Medication medication = givenConnectedMedication();

        guardianMedicationService.update(GUARDIAN_ID, 1L,
                new MedicationUpdateRequest(null, MedicationTimeSlot.DINNER, null, null, null));

        assertThat(medication.getTimeSlot()).isEqualTo(MedicationTimeSlot.DINNER);
        assertThat(medication.getDoseTime()).isEqualTo(LocalTime.of(18, 0));
    }

    @Test
    @DisplayName("시간대와 시각을 함께 주면 지정한 시각을 쓴다(기본값으로 덮어쓰지 않는다)")
    void 시간대_시각_동시변경() {
        Medication medication = givenConnectedMedication();

        guardianMedicationService.update(GUARDIAN_ID, 1L,
                new MedicationUpdateRequest(null, MedicationTimeSlot.DINNER, LocalTime.of(19, 30), null, null));

        assertThat(medication.getTimeSlot()).isEqualTo(MedicationTimeSlot.DINNER);
        assertThat(medication.getDoseTime()).isEqualTo(LocalTime.of(19, 30));
    }

    @Test
    @DisplayName("같은 시간대를 다시 보내면 시각은 유지된다(기본값으로 되돌리지 않는다)")
    void 같은_시간대_재전송() {
        Medication medication = givenConnectedMedication();
        ReflectionTestUtils.setField(medication, "doseTime", LocalTime.of(7, 30));

        guardianMedicationService.update(GUARDIAN_ID, 1L,
                new MedicationUpdateRequest(null, MedicationTimeSlot.MORNING, null, null, null));

        assertThat(medication.getDoseTime()).isEqualTo(LocalTime.of(7, 30));
        verify(reminderLogRepository, never()).deleteByMedicationIdAndDoseDate(anyLong(), any());
    }

    @Test
    @DisplayName("복용 시각이 아직 오지 않은 시각으로 바뀌면 당일 발송 기록을 지워 새 시각으로 다시 알림이 나가게 한다")
    void 시각변경_발송기록_초기화() {
        givenConnectedMedication();

        // 23:59:59 - 실행 시각과 무관하게 "아직 오지 않은" 시각(실시간 시계라 상대 시각을 쓰면 자정 근처에서 되감긴다)
        guardianMedicationService.update(GUARDIAN_ID, 1L,
                new MedicationUpdateRequest(null, null, LocalTime.of(23, 59, 59), null, null));

        verify(reminderLogRepository).deleteByMedicationIdAndDoseDate(1L, MedicationClock.today());
    }

    @Test
    @DisplayName("[MED-G12] 이미 지난 시각으로 바꾸면 발송 기록을 지우지 않는다 - 받은 알림이 다시 나가지 않게")
    void 지난시각_변경_발송기록_유지() {
        Medication medication = givenConnectedMedication();

        // 00:00 - 실행 시각과 무관하게 "지금보다 뒤"가 될 수 없는 시각
        guardianMedicationService.update(GUARDIAN_ID, 1L,
                new MedicationUpdateRequest(null, null, LocalTime.MIN, null, null));

        assertThat(medication.getDoseTime()).isEqualTo(LocalTime.MIN);   // 시각 자체는 바뀐다
        verify(reminderLogRepository, never()).deleteByMedicationIdAndDoseDate(anyLong(), any());
    }

    @Test
    @DisplayName("[MED-G12] 기록 초기화 조건 - 시각이 바뀌었고 새 시각이 지금보다 뒤일 때만")
    void 기록초기화_조건_경계() {
        LocalTime now = LocalTime.of(8, 10);
        LocalTime previous = LocalTime.of(8, 0);

        // 미래로 옮김 → 초기화(08:00 → 20:00, 오늘 저녁에 다시 울려야 한다)
        assertThat(GuardianMedicationService.shouldResetTodayReminders(previous, LocalTime.of(20, 0), now)).isTrue();
        assertThat(GuardianMedicationService.shouldResetTodayReminders(previous, LocalTime.of(8, 11), now)).isTrue();
        // 이미 지난 시각으로 옮김 → 유지(08:00 알림을 받은 뒤 08:05로 고쳐도 재발송하지 않는다)
        assertThat(GuardianMedicationService.shouldResetTodayReminders(previous, LocalTime.of(8, 5), now)).isFalse();
        // 지금과 같은 시각 → 이미 도래했으므로 유지
        assertThat(GuardianMedicationService.shouldResetTodayReminders(previous, now, now)).isFalse();
        // 시각이 그대로면 미래라도 유지
        assertThat(GuardianMedicationService.shouldResetTodayReminders(
                LocalTime.of(20, 0), LocalTime.of(20, 0), now)).isFalse();
    }

    @Test
    @DisplayName("수정해도 복용 체크는 지워지지 않는다 — 이미 드신 약은 계속 완료 상태")
    void 복용체크_유지() {
        givenConnectedMedication();
        when(intakeRepository.findByMedicationIdAndDoseDate(eq(1L), any()))
                .thenReturn(Optional.of(MedicationIntake.of(1L, MedicationClock.today(), OffsetDateTime.now())));

        MedicationItem item = guardianMedicationService.update(GUARDIAN_ID, 1L,
                new MedicationUpdateRequest(null, null, LocalTime.of(23, 59, 59), null, null));

        assertThat(item.taken()).isTrue();
        assertThat(item.takenAt()).isNotNull();
        // 발송 기록만 지우고 체크는 건드리지 않는다
        verify(intakeRepository, never()).delete(any());
    }

    // ─── 메모 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("메모에 빈 문자열을 보내면 삭제된다(null은 미변경이라 지울 수 없으므로)")
    void 메모_삭제() {
        Medication medication = givenConnectedMedication();

        guardianMedicationService.update(GUARDIAN_ID, 1L,
                new MedicationUpdateRequest(null, null, null, null, ""));

        assertThat(medication.getMemo()).isNull();
    }

    @Test
    @DisplayName("[MED-G11] 메모에 공백만 보내도 삭제(null)로 저장한다")
    void 공백메모_삭제() {
        Medication medication = givenConnectedMedication();

        guardianMedicationService.update(GUARDIAN_ID, 1L,
                new MedicationUpdateRequest(null, null, null, null, " \u3000 "));

        assertThat(medication.getMemo()).isNull();
    }

    @Test
    @DisplayName("[MED-G16] 이름·메모의 보이지 않는 문자와 앞뒤 공백은 정리해 저장한다")
    void 이름메모_정리() {
        Medication medication = givenConnectedMedication();

        guardianMedicationService.update(GUARDIAN_ID, 1L,
                new MedicationUpdateRequest(" \u200B당뇨약\u200B ", null, null, null, " 식전\u200B  복용 "));

        assertThat(medication.getName()).isEqualTo("당뇨약");
        assertThat(medication.getMemo()).isEqualTo("식전 복용");
    }

    @Test
    @DisplayName("[MED-G16] 이름에 제로폭 문자만 오면 기존 이름을 유지한다(요청 검증을 우회한 경우의 마지막 방어선)")
    void 제로폭이름_무시() {
        Medication medication = givenConnectedMedication();

        guardianMedicationService.update(GUARDIAN_ID, 1L,
                new MedicationUpdateRequest("\u200B\u200B", null, null, null, null));

        assertThat(medication.getName()).isEqualTo("혈압약");
    }

    @Test
    @DisplayName("이름을 공백만 보내면 기존 이름을 유지한다(빈 이름으로 덮어쓰지 않는다)")
    void 공백_이름_무시() {
        Medication medication = givenConnectedMedication();

        guardianMedicationService.update(GUARDIAN_ID, 1L,
                new MedicationUpdateRequest("   ", null, null, null, null));

        assertThat(medication.getName()).isEqualTo("혈압약");
    }

    // ─── 인가·404 ──────────────────────────────────────────────────

    @Test
    @DisplayName("[IDOR] 연결되지 않은 피보호자의 약 수정 시도 → 403, 아무것도 바뀌지 않는다")
    void 연결없음_차단() {
        Medication medication = medication(OTHER_WARD_ID);
        when(medicationRepository.findById(1L)).thenReturn(Optional.of(medication));
        when(connectionService.isActiveConnection(GUARDIAN_ID, OTHER_WARD_ID)).thenReturn(false);

        assertThatThrownBy(() -> guardianMedicationService.update(GUARDIAN_ID, 1L,
                new MedicationUpdateRequest("바꾼이름", null, null, null, null)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEDICATION_NOT_AUTHORIZED);

        assertThat(medication.getName()).isEqualTo("혈압약");
        verify(reminderLogRepository, never()).deleteByMedicationIdAndDoseDate(anyLong(), any());
    }

    @Test
    @DisplayName("삭제된 약은 수정할 수 없다 → 404 (연결 확인 전에 막힌다)")
    void 삭제된약_404() {
        Medication medication = medication(WARD_ID);
        medication.delete(OffsetDateTime.now());
        when(medicationRepository.findById(1L)).thenReturn(Optional.of(medication));

        assertThatThrownBy(() -> guardianMedicationService.update(GUARDIAN_ID, 1L,
                new MedicationUpdateRequest("바꾼이름", null, null, null, null)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEDICATION_NOT_FOUND);

        verify(connectionService, never()).isActiveConnection(any(), any());
    }

    @Test
    @DisplayName("존재하지 않는 약 수정 → 404")
    void 없는약_404() {
        when(medicationRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guardianMedicationService.update(GUARDIAN_ID, 99L,
                new MedicationUpdateRequest("바꾼이름", null, null, null, null)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEDICATION_NOT_FOUND);
    }

    // ─── 헬퍼 ──────────────────────────────────────────────────────

    /** ACTIVE 연결된 피보호자의 약(오늘 미체크)을 준비한다. */
    private Medication givenConnectedMedication() {
        Medication medication = medication(WARD_ID);
        when(medicationRepository.findById(1L)).thenReturn(Optional.of(medication));
        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(true);
        when(intakeRepository.findByMedicationIdAndDoseDate(eq(1L), any())).thenReturn(Optional.empty());
        return medication;
    }

    private static Medication medication(String wardId) {
        Medication medication = Medication.builder()
                .wardId(wardId)
                .createdBy(GUARDIAN_ID)
                .name("혈압약")
                .timeSlot(MedicationTimeSlot.MORNING)
                .doseTime(LocalTime.of(8, 0))
                .doseAmount(1)
                .memo("식후 30분")
                .build();
        ReflectionTestUtils.setField(medication, "id", 1L);
        return medication;
    }
}

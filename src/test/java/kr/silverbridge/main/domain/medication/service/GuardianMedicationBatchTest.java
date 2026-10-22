package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.medication.dto.MedicationBatchCreateRequest;
import kr.silverbridge.main.domain.medication.dto.MedicationCreateRequest;
import kr.silverbridge.main.domain.medication.dto.MedicationItem;
import kr.silverbridge.main.domain.medication.dto.MedicationUpdateRequest;
import kr.silverbridge.main.domain.medication.entity.Medication;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 약 일괄 등록(2026-10-22) - 약 × 시각 조합, 시간대 자동 결정, 단건 등록과 같은 정리 규칙, 인가·중복 거절.
 * 원자성(중간 항목 실패 시 0건)은 실 DB 통합 테스트 {@code MedicationHalfDoseBatchIntegrationTest}가 확인한다.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class GuardianMedicationBatchTest {

    private static final String GUARDIAN = "GD0001";
    private static final String WARD = "WD0001";

    @Mock private MedicationRepository medicationRepository;
    @Mock private MedicationIntakeRepository intakeRepository;
    @Mock private MedicationReminderLogRepository reminderLogRepository;
    @Mock private MedicationSettingService settingService;
    @Mock private GuardianMedicationSettingService guardianSettingService;
    @Mock private ConnectionService connectionService;
    @Mock private UserRepository userRepository;

    @InjectMocks private GuardianMedicationService service;

    private static MedicationBatchCreateRequest.Drug drug(String name, String amount) {
        return new MedicationBatchCreateRequest.Drug(name, amount == null ? null : new BigDecimal(amount));
    }

    private static MedicationBatchCreateRequest.Time at(int hour, int minute) {
        return new MedicationBatchCreateRequest.Time(LocalTime.of(hour, minute));
    }

    @SuppressWarnings("unchecked")
    private List<Medication> savedMedications() {
        ArgumentCaptor<List<Medication>> captor = ArgumentCaptor.forClass(List.class);
        verify(medicationRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("약 6 × 시각 2 = 12건 - 시각 순·약 순서, 시간대는 시각으로, 메모·이름 정리는 단건과 같다")
    void sixByTwoCreatesTwelve() {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
        when(medicationRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        List<MedicationBatchCreateRequest.Drug> drugs = List.of(drug(" 약1​ ", "0.5"), drug("약2", null),
                drug("약3", "1"), drug("약4", "1.5"), drug("약5", "2"), drug("약6", "3"));

        List<MedicationItem> items = service.createBatch(GUARDIAN, WARD,
                new MedicationBatchCreateRequest(drugs, List.of(at(19, 30), at(7, 0)), "   "));

        List<Medication> saved = savedMedications();
        assertThat(saved).hasSize(12);
        assertThat(saved.subList(0, 6)).allSatisfy(m -> {
            assertThat(m.getDoseTime()).isEqualTo(LocalTime.of(7, 0));
            assertThat(m.getTimeSlot()).isEqualTo(MedicationTimeSlot.MORNING);
        });
        assertThat(saved.subList(6, 12)).allSatisfy(m -> {
            assertThat(m.getDoseTime()).isEqualTo(LocalTime.of(19, 30));
            assertThat(m.getTimeSlot()).isEqualTo(MedicationTimeSlot.DINNER);
        });
        assertThat(saved).allSatisfy(m -> {
            assertThat(m.getWardId()).isEqualTo(WARD);
            assertThat(m.getCreatedBy()).isEqualTo(GUARDIAN);
            assertThat(m.getMemo()).isNull();
        });
        assertThat(saved.get(0).getName()).isEqualTo("약1");
        assertThat(saved.get(0).getDoseAmount()).isEqualByComparingTo("0.5");
        assertThat(saved.get(1).getDoseAmount()).isEqualByComparingTo("1");
        assertThat(items).hasSize(12).allSatisfy(item -> assertThat(item.taken()).isFalse());
        assertThat(items.get(0).doseAmount().toPlainString()).isEqualTo("0.5");
    }

    @Test
    @DisplayName("ACTIVE 연결이 아니면 403 + [IDOR-ATTEMPT], 아무것도 저장하지 않는다")
    void notConnected(CapturedOutput output) {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(false);

        assertThatThrownBy(() -> service.createBatch(GUARDIAN, WARD,
                new MedicationBatchCreateRequest(List.of(drug("약1", null)), List.of(at(8, 0)), null)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.MEDICATION_NOT_AUTHORIZED);
        verify(medicationRepository, never()).saveAll(anyList());
        assertThat(output).contains("[IDOR-ATTEMPT]", "guardianId=" + GUARDIAN, "wardId=" + WARD);
    }

    @Test
    @DisplayName("같은 요청 안의 같은 이름(정리 후·대소문자 무시)·같은 시각은 400, 저장 없음")
    void duplicatesRejected() {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);

        assertThatThrownBy(() -> service.createBatch(GUARDIAN, WARD, new MedicationBatchCreateRequest(
                List.of(drug("Tylenol", null), drug(" tylenol​", null)), List.of(at(8, 0)), null)))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.MEDICATION_BATCH_DUPLICATE_NAME);
        assertThatThrownBy(() -> service.createBatch(GUARDIAN, WARD, new MedicationBatchCreateRequest(
                List.of(drug("약1", null)), List.of(at(8, 0), at(8, 0)), null)))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.MEDICATION_BATCH_DUPLICATE_TIME);
        verify(medicationRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("단건 등록·수정도 반 알을 저장하고, 응답은 1.0 이 아니라 1·0.5")
    void singleCreateAndUpdateKeepHalfDose() {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
        when(medicationRepository.save(any(Medication.class))).thenAnswer(inv -> inv.getArgument(0));

        MedicationItem created = service.create(GUARDIAN, WARD, new MedicationCreateRequest(
                "감기약", MedicationTimeSlot.DINNER, null, new BigDecimal("0.5"), null));
        assertThat(created.doseAmount().toPlainString()).isEqualTo("0.5");

        Medication stored = Medication.builder().wardId(WARD).createdBy(GUARDIAN).name("혈압약")
                .timeSlot(MedicationTimeSlot.MORNING).doseTime(LocalTime.of(8, 0)).doseAmount(new BigDecimal("1.0")).build();
        when(medicationRepository.findById(1L)).thenReturn(Optional.of(stored));
        when(intakeRepository.findByMedicationIdAndDoseDate(any(), any())).thenReturn(Optional.empty());

        MedicationItem unchanged = service.update(GUARDIAN, 1L, new MedicationUpdateRequest("혈압약2", null, null, null, null));
        assertThat(unchanged.doseAmount().toPlainString()).isEqualTo("1");
        MedicationItem half = service.update(GUARDIAN, 1L, new MedicationUpdateRequest(null, null, null, new BigDecimal("1.5"), null));
        assertThat(half.doseAmount().toPlainString()).isEqualTo("1.5");
        assertThat(stored.getDoseAmount()).isEqualByComparingTo("1.5");
    }
}

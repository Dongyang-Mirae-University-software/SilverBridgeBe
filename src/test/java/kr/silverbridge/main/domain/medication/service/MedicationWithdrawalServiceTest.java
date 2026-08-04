package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import kr.silverbridge.main.domain.medication.repository.MedicationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MedicationWithdrawalService 단위 테스트.
 *
 * <p>보호자 탈퇴 시 그 보호자가 등록한 약을 지우고, <b>남은 보호자에게 안내할 건수</b>를 정확히 집계하는지를
 * 본다. 안내 문구의 숫자가 사실과 달라지면 안 되므로 "이미 삭제된 약"은 건수에서 빠져야 한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class MedicationWithdrawalServiceTest {

    @Mock private MedicationRepository medicationRepository;

    @InjectMocks private MedicationWithdrawalService medicationWithdrawalService;

    private static final String GUARDIAN_ID = "GD0001";
    private static final String WARD_ID = "WD0001";
    private static final String OTHER_WARD_ID = "WD0002";

    @Test
    @DisplayName("피보호자별로 중지 건수를 집계하고 등록한 약을 모두 삭제한다")
    void 피보호자별_집계_삭제() {
        List<Medication> medications = List.of(
                medication(WARD_ID, "혈압약"),
                medication(WARD_ID, "당뇨약"),
                medication(OTHER_WARD_ID, "관절약"));
        when(medicationRepository.findByCreatedBy(GUARDIAN_ID)).thenReturn(medications);

        Map<String, Integer> stopped = medicationWithdrawalService.removeMedicationsRegisteredBy(GUARDIAN_ID);

        assertThat(stopped).containsExactlyInAnyOrderEntriesOf(Map.of(WARD_ID, 2, OTHER_WARD_ID, 1));
        verify(medicationRepository).deleteAll(medications);
    }

    @Test
    @DisplayName("이미 삭제된 약은 중지 건수에서 빼되, 삭제 대상에는 포함한다")
    void 이미삭제된약은_건수제외_삭제포함() {
        Medication active = medication(WARD_ID, "혈압약");
        Medication alreadyDeleted = medication(WARD_ID, "예전에 끊은 약");
        alreadyDeleted.delete(OffsetDateTime.now());
        List<Medication> medications = List.of(active, alreadyDeleted);
        when(medicationRepository.findByCreatedBy(GUARDIAN_ID)).thenReturn(medications);

        Map<String, Integer> stopped = medicationWithdrawalService.removeMedicationsRegisteredBy(GUARDIAN_ID);

        assertThat(stopped).containsExactlyEntriesOf(Map.of(WARD_ID, 1));
        // 등록자가 사라지므로 이미 삭제된 약도 행을 남기지 않는다.
        verify(medicationRepository).deleteAll(medications);
    }

    @Test
    @DisplayName("등록한 약이 없으면 빈 맵 — 삭제도 호출하지 않는다(피보호자 탈퇴 시 이 경로)")
    void 등록한약없음_아무것도안함() {
        when(medicationRepository.findByCreatedBy(GUARDIAN_ID)).thenReturn(List.of());

        assertThat(medicationWithdrawalService.removeMedicationsRegisteredBy(GUARDIAN_ID)).isEmpty();

        verify(medicationRepository, never()).deleteAll(any());
    }

    private static Medication medication(String wardId, String name) {
        return Medication.builder()
                .wardId(wardId)
                .createdBy(GUARDIAN_ID)
                .name(name)
                .timeSlot(MedicationTimeSlot.MORNING)
                .doseTime(LocalTime.of(8, 0))
                .doseAmount(1)
                .build();
    }
}

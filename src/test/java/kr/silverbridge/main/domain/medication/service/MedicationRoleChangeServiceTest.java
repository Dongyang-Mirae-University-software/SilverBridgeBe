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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MedicationRoleChangeServiceTest {

    private static final String WARD_ID = "WARD01";

    @Mock private MedicationRepository medicationRepository;

    @InjectMocks private MedicationRoleChangeService service;

    @Test
    @DisplayName("피보호자로서 갖고 있던 살아 있는 약을 모두 중지(soft delete)하고 건수를 돌려준다")
    void 살아있는_약을_모두_중지한다() {
        Medication morning = medication("혈압약", LocalTime.of(8, 0));
        Medication dinner = medication("당뇨약", LocalTime.of(18, 0));
        when(medicationRepository.findByWardIdAndDeletedAtIsNullOrderByDoseTimeAscIdAsc(WARD_ID))
                .thenReturn(List.of(morning, dinner));

        int stopped = service.stopAllOwnedByWard(WARD_ID);

        assertThat(stopped).isEqualTo(2);
        // 행을 지우지 않는다 - 복용 이력을 보존하려고 보호자의 일반 삭제와 같은 방식(soft delete)을 쓴다
        assertThat(List.of(morning, dinner)).allMatch(Medication::isDeleted);
    }

    @Test
    @DisplayName("정리할 약이 없으면 0을 돌려준다 (보호자였던 사람의 역할 변경 포함)")
    void 약이_없으면_0건() {
        when(medicationRepository.findByWardIdAndDeletedAtIsNullOrderByDoseTimeAscIdAsc(WARD_ID))
                .thenReturn(List.of());

        assertThat(service.stopAllOwnedByWard(WARD_ID)).isZero();
    }

    private static Medication medication(String name, LocalTime doseTime) {
        return Medication.builder()
                .wardId(WARD_ID)
                .createdBy("GRD001")
                .name(name)
                .timeSlot(MedicationTimeSlot.MORNING)
                .doseTime(doseTime)
                .doseAmount(1)
                .build();
    }
}

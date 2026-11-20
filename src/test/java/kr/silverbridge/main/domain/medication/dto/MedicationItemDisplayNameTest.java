package kr.silverbridge.main.domain.medication.dto;

import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class MedicationItemDisplayNameTest {

    @Test
    @DisplayName("카드 응답은 저장된 원본 name과 화면용 displayName을 함께 준다")
    void itemCarriesOriginalAndDisplayName() {
        Medication medication = Medication.builder()
                .wardId("ward01")
                .createdBy("grd001")
                .name("다이아벡스정500밀리그램(메트포르민염산염)")
                .timeSlot(MedicationTimeSlot.MORNING)
                .doseTime(LocalTime.of(8, 0))
                .doseAmount(BigDecimal.ONE)
                .build();

        MedicationItem item = MedicationItem.of(medication, null);

        assertThat(item.name()).isEqualTo("다이아벡스정500밀리그램(메트포르민염산염)");
        assertThat(item.displayName()).isEqualTo("다이아벡스정");
    }
}

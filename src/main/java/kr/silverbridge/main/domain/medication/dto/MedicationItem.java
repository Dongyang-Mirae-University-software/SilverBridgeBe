package kr.silverbridge.main.domain.medication.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.entity.MedicationIntake;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;

import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * 복약 일정 한 건. 보호자 화면·피보호자 화면이 같은 형태를 쓴다.
 *
 * <p>표시 문구("아침 08:00 · 1정 · 식후 30분")는 프론트가 조립한다 — 서버는 원자값만 준다.</p>
 *
 * @param taken   조회 기준일(오늘)에 복용 체크가 되었는지. 체크는 피보호자만 할 수 있다.
 * @param takenAt 체크한 시각. 미복용이면 null.
 */
@Schema(description = "복약 일정 항목")
public record MedicationItem(

        @Schema(description = "약 ID", example = "12")
        Long medicationId,

        @Schema(description = "약 이름", example = "혈압약 (암로디핀 5mg)")
        String name,

        @Schema(description = "복용 시간대", example = "MORNING",
                allowableValues = {"MORNING", "LUNCH", "DINNER", "BEDTIME"})
        MedicationTimeSlot timeSlot,

        @Schema(description = "복용 시각", example = "08:00:00", type = "string")
        LocalTime doseTime,

        @Schema(description = "복용량(정)", example = "1")
        int doseAmount,

        @Schema(description = "복용 안내 메모 (없으면 null)", example = "식후 30분")
        String memo,

        @Schema(description = "오늘 복용 체크 여부", example = "true")
        boolean taken,

        @Schema(description = "복용 체크 시각 (미복용이면 null)")
        OffsetDateTime takenAt
) {
    /** @param intake 해당 날짜의 복용 체크. 없으면 {@code null}(미복용). */
    public static MedicationItem of(Medication medication, MedicationIntake intake) {
        return new MedicationItem(
                medication.getId(),
                medication.getName(),
                medication.getTimeSlot(),
                medication.getDoseTime(),
                medication.getDoseAmount(),
                medication.getMemo(),
                intake != null,
                intake != null ? intake.getTakenAt() : null
        );
    }
}

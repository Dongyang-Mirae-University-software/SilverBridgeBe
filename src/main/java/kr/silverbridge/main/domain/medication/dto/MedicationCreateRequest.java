package kr.silverbridge.main.domain.medication.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;

import java.time.LocalTime;

/**
 * 약 추가 요청(보호자 전용).
 *
 * @param doseTime 생략하면 {@code timeSlot}의 기본 시각을 쓴다(아침 08:00 / 점심 13:00 / 저녁 18:00 / 취침 전 22:00).
 */
@Schema(description = "약 추가 요청")
public record MedicationCreateRequest(

        @NotBlank(message = "약 이름은 필수입니다.")
        @Size(max = 100, message = "약 이름은 100자를 초과할 수 없습니다.")
        @Schema(description = "약 이름", example = "혈압약 (암로디핀 5mg)")
        String name,

        @NotNull(message = "복용 시간대는 필수입니다.")
        @Schema(description = "복용 시간대", example = "MORNING",
                allowableValues = {"MORNING", "LUNCH", "DINNER", "BEDTIME"})
        MedicationTimeSlot timeSlot,

        @Schema(description = "복용 시각 (생략 시 시간대 기본값)", example = "08:00:00", type = "string")
        LocalTime doseTime,

        @Min(value = 1, message = "복용량은 1 이상이어야 합니다.")
        @Max(value = 99, message = "복용량은 99를 초과할 수 없습니다.")
        @Schema(description = "복용량(정)", example = "1", defaultValue = "1")
        Integer doseAmount,

        @Size(max = 100, message = "메모는 100자를 초과할 수 없습니다.")
        @Schema(description = "복용 안내 메모 (선택)", example = "식사와 함께")
        String memo
) {
    /** 복용량 미지정 시 기본값. 화면의 복용량 스테퍼 초기값과 같다. */
    private static final int DEFAULT_DOSE_AMOUNT = 1;

    /** 시각 미지정 시 시간대 기본값으로 보정한다. */
    public LocalTime resolveDoseTime() {
        return doseTime != null ? doseTime : timeSlot.defaultTime();
    }

    public int resolveDoseAmount() {
        return doseAmount != null ? doseAmount : DEFAULT_DOSE_AMOUNT;
    }
}

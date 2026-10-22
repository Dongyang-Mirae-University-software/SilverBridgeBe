package kr.silverbridge.main.domain.medication.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import kr.silverbridge.main.global.validation.NoControlChars;
import kr.silverbridge.main.global.validation.VisibleText;

import kr.silverbridge.main.domain.medication.entity.DoseAmounts;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 약 추가 요청(보호자 전용).
 *
 * @param doseTime 생략하면 {@code timeSlot}의 기본 시각을 쓴다(아침 08:00 / 점심 13:00 / 저녁 18:00 / 취침 전 22:00).
 */
@Schema(description = "약 추가 요청")
public record MedicationCreateRequest(

        // 제로폭 공백만 넣은 이름은 @NotBlank를 통과하지만 화면·알림에 빈 이름으로 보인다(MED-G16).
        @NotBlank(message = "약 이름은 필수입니다.")
        @VisibleText(message = "약 이름을 입력해주세요.")
        @NoControlChars
        @Size(max = 100, message = "약 이름은 100자를 초과할 수 없습니다.")
        @Schema(description = "약 이름", example = "혈압약 (암로디핀 5mg)")
        String name,

        @NotNull(message = "복용 시간대는 필수입니다.")
        @Schema(description = "복용 시간대", example = "MORNING",
                allowableValues = {"MORNING", "LUNCH", "DINNER", "BEDTIME"})
        MedicationTimeSlot timeSlot,

        @Schema(description = "복용 시각 (생략 시 시간대 기본값)", example = "08:00:00", type = "string")
        LocalTime doseTime,

        @DoseAmount
        @Schema(description = "복용량(정). 0.5 단위(반 알 = 0.5), 0.5~99. 정수(1, 2)도 그대로 받는다", example = "1", defaultValue = "1")
        BigDecimal doseAmount,

        // 줄바꿈은 막지 않는다(입력창에 따라 섞여 올 수 있다) - 저장 전 한 줄로 정리된다.
        @NoControlChars(allowLineBreaks = true)
        @Size(max = 100, message = "메모는 100자를 초과할 수 없습니다.")
        @Schema(description = "복용 안내 메모 (선택, 공백만 보내면 메모 없음)", example = "식사와 함께")
        String memo
) {
    /** 시각 미지정 시 시간대 기본값으로 보정한다. */
    public LocalTime resolveDoseTime() {
        return doseTime != null ? doseTime : timeSlot.defaultTime();
    }

    public BigDecimal resolveDoseAmount() {
        return doseAmount != null ? doseAmount : DoseAmounts.DEFAULT;
    }
}

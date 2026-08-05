package kr.silverbridge.main.domain.medication.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;

import java.time.LocalTime;

/**
 * 약 부분 수정 요청. <b>전달한 필드만 갱신</b>하며 {@code null}은 미변경이다
 * ({@code CameraUpdateRequest}와 같은 방식).
 *
 * <p>{@code wardId}·{@code createdBy}는 수정 대상이 아니다 — 다른 피보호자에게 약을 옮기는 것은
 * 삭제 후 재등록으로 처리한다.</p>
 *
 * @param doseTime {@code timeSlot}만 바꾸고 이 값을 생략하면 <b>새 시간대의 기본 시각</b>으로 갱신된다
 *                 (아침→저녁이면 18:00). 그러지 않으면 "저녁 08:00" 같은 상태가 남는다.
 * @param memo     메모를 지우려면 <b>빈 문자열</b>을 보낸다({@code null}은 미변경이라 지울 수 없다).
 */
@Schema(description = "약 수정 요청 (부분 수정 — null 필드는 미변경)")
public record MedicationUpdateRequest(

        @Size(min = 1, max = 100, message = "약 이름은 1~100자여야 합니다.")
        @Schema(description = "약 이름", example = "혈압약 (암로디핀 5mg)", nullable = true)
        String name,

        @Schema(description = "복용 시간대", example = "DINNER", nullable = true,
                allowableValues = {"MORNING", "LUNCH", "DINNER", "BEDTIME"})
        MedicationTimeSlot timeSlot,

        @Schema(description = "복용 시각 (시간대만 바꾸고 생략하면 새 시간대 기본값)",
                example = "18:00:00", type = "string", nullable = true)
        LocalTime doseTime,

        @Min(value = 1, message = "복용량은 1 이상이어야 합니다.")
        @Max(value = 99, message = "복용량은 99를 초과할 수 없습니다.")
        @Schema(description = "복용량(정)", example = "2", nullable = true)
        Integer doseAmount,

        @Size(max = 100, message = "메모는 100자를 초과할 수 없습니다.")
        @Schema(description = "복용 안내 메모 (빈 문자열이면 메모 삭제)", example = "식사와 함께", nullable = true)
        String memo
) {}

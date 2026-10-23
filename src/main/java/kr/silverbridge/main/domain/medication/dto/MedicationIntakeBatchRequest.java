package kr.silverbridge.main.domain.medication.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 복용 묶음 체크 요청(피보호자 전용, 2026-10-23). 같은 시간대의 약을 한 번에 체크·해제한다 - 전부 성공 또는 전부 실패.
 * 약 하나씩 체크하는 단건 API와 판정(본인 약·삭제·멱등)이 같다.
 */
@Schema(description = "복용 묶음 체크 요청 - 전부 성공 또는 전부 실패")
public record MedicationIntakeBatchRequest(

        @NotNull(message = "약 목록은 필수입니다.")
        @Size(min = 1, max = 50, message = "약은 1~50개까지 한 번에 체크할 수 있습니다.")
        @Schema(description = "체크할 약 ID 목록(1~50개, 같은 ID 중복 불가)", example = "[11, 12, 13]")
        List<@NotNull(message = "약 ID가 비어 있습니다.") Long> medicationIds,

        @NotNull(message = "체크 여부는 필수입니다.")
        @Schema(description = "true = 먹었어요(체크), false = 체크 해제", example = "true")
        Boolean taken
) {
}

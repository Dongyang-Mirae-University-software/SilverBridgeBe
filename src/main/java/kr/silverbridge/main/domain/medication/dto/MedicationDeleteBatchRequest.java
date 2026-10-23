package kr.silverbridge.main.domain.medication.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 약 여러 개 삭제 요청(보호자 전용, 2026-10-23). "아침 약 모두 삭제"·"약 전부 삭제" - 전부 성공 또는 전부 실패.
 * "전부"라는 신호 대신 화면에 보이는 약 ID를 받는다 - 다른 보호자가 방금 추가한 약까지 지우지 않게.
 */
@Schema(description = "약 여러 개 삭제 요청 - 전부 성공 또는 전부 실패, 지난 복용 이력은 남는다")
public record MedicationDeleteBatchRequest(

        @NotNull(message = "약 목록은 필수입니다.")
        @Size(min = 1, max = 100, message = "약은 1~100개까지 한 번에 삭제할 수 있습니다.")
        @Schema(description = "삭제할 약 ID 목록(1~100개, 같은 ID 중복 불가)", example = "[11, 12, 13]")
        List<@NotNull(message = "약 ID가 비어 있습니다.") Long> medicationIds
) {
}

package kr.silverbridge.main.domain.anomaly.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;

/**
 * 관리자 판정 정정 요청.
 *
 * @param reviewStatus REAL 또는 FALSE_ALARM만 받는다. PENDING·CONFLICTED로 되돌리는 것은 막는다 -
 *                     관리자가 확인을 마친 뒤에 "아직 아무도 답하지 않음"이나 "엇갈림"으로 되돌리면
 *                     그 상태가 무엇을 뜻하는지 알 수 없게 된다. 판단을 못 하겠으면 그냥 두면 된다.
 * @param note         정정 사유. 선택이며 200자까지. 무엇을 근거로 뒤집었는지 남겨 두면 나중에
 *                     같은 유형의 오탐을 판단할 때 참고가 된다.
 */
@Schema(description = "이상감지 판정 정정 요청")
public record AdminAnomalyReviewRequest(

        @Schema(description = "정정할 판정 상태", example = "FALSE_ALARM",
                allowableValues = {"REAL", "FALSE_ALARM"}, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "판정 상태는 필수입니다.")
        AnomalyReviewStatus reviewStatus,

        @Schema(description = "정정 사유 (선택, 200자)", example = "보호자 통화 확인 - 요리 연기")
        @Size(max = 200, message = "정정 사유는 200자를 넘을 수 없습니다.")
        String note
) {
}

package kr.silverbridge.main.domain.anomaly.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyVerdict;

/**
 * 응답 직후의 상황 상태. 재계산 결과를 돌려주므로 프론트가 목록을 다시 부르지 않아도 화면을 갱신할 수 있다.
 *
 * <p>{@code reviewStatus}가 {@code CONFLICTED}로 돌아올 수 있다 - 내 응답이 다른 보호자와 갈렸다는 뜻이며,
 * 내 응답이 거부된 것이 아니다.</p>
 */
@Schema(description = "이상감지 오탐 응답 결과")
public record AnomalyFeedbackResponse(

        @Schema(description = "상황 ID", example = "37")
        Long incidentId,

        @Schema(description = "재계산된 판정 상태", example = "FALSE_ALARM",
                allowableValues = {"PENDING", "REAL", "FALSE_ALARM", "CONFLICTED"})
        AnomalyReviewStatus reviewStatus,

        @Schema(description = "내가 낸 응답", example = "FALSE_ALARM",
                allowableValues = {"REAL", "FALSE_ALARM"})
        AnomalyVerdict myVerdict
) {}

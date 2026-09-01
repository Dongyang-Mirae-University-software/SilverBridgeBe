package kr.silverbridge.main.domain.anomaly.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyVerdict;

/**
 * 보호자의 오탐 응답.
 *
 * <p>사유를 받지 않는다 - 시니어/4050 보호자에게 자유 입력을 요구하면 응답률이 떨어지고, 판정에 필요한
 * 것은 "실제였나 아니었나" 한 가지다. 엇갈린 건의 배경은 관리자가 정정하며 메모로 남긴다.</p>
 */
@Schema(description = "이상감지 오탐 응답 요청")
public record AnomalyFeedbackRequest(

        @Schema(description = "판단", example = "FALSE_ALARM", allowableValues = {"REAL", "FALSE_ALARM"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "판단을 선택해주세요.")
        AnomalyVerdict verdict
) {}

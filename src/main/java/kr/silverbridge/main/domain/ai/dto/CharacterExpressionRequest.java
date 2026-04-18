package kr.silverbridge.main.domain.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Schema(description = "AI 서버 → 캐릭터 표정 전달 요청")
@Getter
public class CharacterExpressionRequest {

    @Schema(description = "피보호자 사용자 ID", example = "AB1234")
    @NotBlank(message = "wardId를 입력해주세요.")
    private String wardId;

    @Schema(description = "AI가 감지한 표정 (자유 문자열, 예: HAPPY, SAD, PAIN 등)", example = "SAD")
    @NotBlank(message = "표정을 입력해주세요.")
    private String expression;

    @Schema(description = "신뢰도 (0.0 ~ 1.0)", example = "0.92")
    @NotNull(message = "신뢰도를 입력해주세요.")
    @DecimalMin(value = "0.0", message = "신뢰도는 0.0 이상이어야 합니다.")
    @DecimalMax(value = "1.0", message = "신뢰도는 1.0 이하여야 합니다.")
    private Double confidence;

    @Schema(description = "보호자 FCM 알림 발송 여부 (AI팀이 이상 표정 여부를 판단하여 설정)", example = "false")
    @NotNull(message = "needsAlert를 입력해주세요.")
    private Boolean needsAlert;
}

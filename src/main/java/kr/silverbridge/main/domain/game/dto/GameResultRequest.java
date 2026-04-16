package kr.silverbridge.main.domain.game.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import kr.silverbridge.main.global.enums.GameType;
import lombok.Getter;

@Schema(description = "게임 결과 저장 요청")
@Getter
public class GameResultRequest {

    @Schema(description = "게임 유형", allowableValues = {"MATCHING", "WORD_QUIZ", "ADDITION", "SUBTRACTION"}, example = "MATCHING")
    @NotNull(message = "게임 유형을 입력해주세요.")
    private GameType gameType;

    @Schema(description = "난이도 (1=쉬움, 2=보통, 3=어려움)", example = "2")
    @NotNull(message = "난이도를 입력해주세요.")
    @Min(value = 1, message = "난이도는 1 이상이어야 합니다.")
    @Max(value = 3, message = "난이도는 3 이하여야 합니다.")
    private Integer difficulty;

    @Schema(description = "클리어 여부", example = "true")
    @NotNull(message = "클리어 여부를 입력해주세요.")
    private Boolean isCleared;

    @Schema(description = "점수 (없으면 null)", example = "850", nullable = true)
    private Integer score;

    @Schema(description = "게임 소요 시간 (초)", example = "45", nullable = true)
    private Integer durationSeconds;
}

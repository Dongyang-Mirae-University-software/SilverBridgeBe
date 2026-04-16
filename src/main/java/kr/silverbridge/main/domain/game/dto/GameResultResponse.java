package kr.silverbridge.main.domain.game.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.game.entity.GameResult;
import lombok.Getter;

import java.time.OffsetDateTime;

@Schema(description = "게임 결과 응답")
@Getter
public class GameResultResponse {

    @Schema(description = "게임 결과 ID")
    private final Long id;

    @Schema(description = "게임 유형", allowableValues = {"MATCHING", "WORD_QUIZ", "ADDITION", "SUBTRACTION"})
    private final String gameType;

    @Schema(description = "난이도 (1~3)")
    private final int difficulty;

    @Schema(description = "클리어 여부")
    private final boolean isCleared;

    @Schema(description = "점수", nullable = true)
    private final Integer score;

    @Schema(description = "소요 시간 (초)", nullable = true)
    private final Integer durationSeconds;

    @Schema(description = "플레이 시각", example = "2025-01-01T09:00:00+09:00")
    private final OffsetDateTime playedAt;

    private GameResultResponse(Long id, String gameType, int difficulty, boolean isCleared,
                               Integer score, Integer durationSeconds, OffsetDateTime playedAt) {
        this.id = id;
        this.gameType = gameType;
        this.difficulty = difficulty;
        this.isCleared = isCleared;
        this.score = score;
        this.durationSeconds = durationSeconds;
        this.playedAt = playedAt;
    }

    public static GameResultResponse from(GameResult result) {
        return new GameResultResponse(
                result.getId(),
                result.getGameType().name(),
                result.getDifficulty(),
                result.isCleared(),
                result.getScore(),
                result.getDurationSeconds(),
                result.getPlayedAt()
        );
    }
}

package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.game.entity.GameResult;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.enums.GameType;

import java.time.OffsetDateTime;

@Schema(description = "게임 결과 응답")
public record GameResultResponse(

        @Schema(description = "게임 결과 ID", example = "1")
        Long id,

        @Schema(description = "피보호자 UUID", example = "uuid-ward-1234")
        String userId,

        @Schema(description = "피보호자 이름", example = "홍길동")
        String userName,

        @Schema(description = "피보호자 이메일", example = "ward@example.com")
        String userEmail,

        @Schema(description = "게임 유형", allowableValues = {"MATCHING", "WORD_QUIZ", "ADDITION", "SUBTRACTION"}, example = "MATCHING")
        GameType gameType,

        @Schema(description = "난이도 (1~3)", example = "1")
        int difficulty,

        @Schema(description = "클리어 여부", example = "true")
        boolean isCleared,

        @Schema(description = "점수 (없을 수 있음)", example = "80", nullable = true)
        Integer score,

        @Schema(description = "플레이 시간 (초, 없을 수 있음)", example = "120", nullable = true)
        Integer durationSeconds,

        @Schema(description = "플레이 일시")
        OffsetDateTime playedAt
) {

    public static GameResultResponse of(GameResult result, User user) {
        return new GameResultResponse(
                result.getId(),
                user.getId(),
                user.getName(),
                user.getEmail(),
                result.getGameType(),
                result.getDifficulty(),
                result.isCleared(),
                result.getScore(),
                result.getDurationSeconds(),
                result.getPlayedAt()
        );
    }
}

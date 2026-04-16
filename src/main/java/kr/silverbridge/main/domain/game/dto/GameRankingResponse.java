package kr.silverbridge.main.domain.game.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Schema(description = "게임 랭킹 항목")
@Getter
public class GameRankingResponse {

    @Schema(description = "순위")
    private final int rank;

    @Schema(description = "사용자 ID")
    private final String userId;

    @Schema(description = "사용자 이름")
    private final String userName;

    @Schema(description = "총 플레이 횟수")
    private final long totalCount;

    @Schema(description = "클리어 횟수")
    private final long clearedCount;

    @Schema(description = "클리어율 (%)", example = "75.0")
    private final double clearRate;

    @Schema(description = "평균 점수")
    private final double avgScore;

    public GameRankingResponse(int rank, String userId, String userName,
                               long totalCount, long clearedCount, double avgScore) {
        this.rank = rank;
        this.userId = userId;
        this.userName = userName;
        this.totalCount = totalCount;
        this.clearedCount = clearedCount;
        this.clearRate = totalCount > 0 ? (double) clearedCount / totalCount * 100 : 0;
        this.avgScore = avgScore;
    }
}

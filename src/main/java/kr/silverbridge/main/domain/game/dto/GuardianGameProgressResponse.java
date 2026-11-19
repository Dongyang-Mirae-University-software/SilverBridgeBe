package kr.silverbridge.main.domain.game.dto;

import java.util.List;

/**
 * 보호자 게임 관리 - 피보호자 게임 진행 요약 (AI {@code GET /api/v1/games/progress} 중계).
 *
 * <p>허용 목록 record다. AI 응답의 게임 내부 상태({@code state})와 게임 사용자 번호({@code userId})는 싣지 않고 {@code wardId}를 싣는다.
 * 시각({@code lastPlayedAt} 등)은 AI 문자열을 <b>그대로</b> 넘긴다(오프셋 없는 UTC - FE가 UTC로 읽는다, FE #259).</p>
 */
public record GuardianGameProgressResponse(
        String wardId,
        long totalScore,
        long totalAttempts,
        String lastPlayedAt,
        List<GameEntry> games
) {

    public record GameEntry(Catalog game, Progress progress) {
    }

    public record Catalog(String slug, String title, String description, Integer totalStages, String themeColor) {
    }

    /** 아직 한 번도 하지 않은 게임은 {@code progress}가 null이다. */
    public record Progress(
            String gameSlug,
            Integer currentStageNo,
            Long score,
            Long attempts,
            Boolean cleared,
            Boolean lastAnswerCorrect,
            String startedAt,
            String updatedAt,
            String clearedAt
    ) {
    }
}

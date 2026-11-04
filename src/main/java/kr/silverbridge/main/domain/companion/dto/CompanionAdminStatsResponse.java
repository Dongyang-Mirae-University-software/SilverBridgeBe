package kr.silverbridge.main.domain.companion.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 관리자 "AI 통계 > AI 안부 말벗·정서 분석" 탭과 대시보드 카드(2026-11-04, PR-B, 노션 카드 "AI 통계 집계 API").
 * 숫자·기분 코드·어르신 이름만 담는다 - 대답 원문·한 줄 요약은 없다. 모르는 값은 0이 아니라 null이다.
 */
@Schema(description = "안부 말벗 오늘 집계 (KST)")
public record CompanionAdminStatsResponse(
        @Schema(description = "KST 날짜", example = "2026-11-04") String date,
        @Schema(description = "오늘 보낸 안부 질문 안내 수", example = "170") int askedCount,
        @Schema(description = "어르신 응답률(%) = 대답한 질문 수 ÷ 보낸 질문 수. 보낸 질문이 없으면 null", example = "82")
        Integer responseRate,
        @Schema(description = "오늘 질문을 받았지만 대답한 대화가 없는 어르신 수", example = "4") int noDialogueWardCount,
        @Schema(description = "오늘 보낸 기분 변화 알림 수(주의 알림 + 위험 신호 알림)", example = "3") long alertSentCount,
        @Schema(description = "오늘 기분 분포 - 어르신별 오늘 마지막으로 마친 대화 기준") MoodDistribution moodDistribution,
        @Schema(description = "걱정·우울이 3일 이상 이어지는 어르신(며칠째 많은 순)") List<ContinuingWard> continuingWards,
        @Schema(description = "오늘 대화가 없는 어르신(질문은 받았으나 대답 없음)") List<NoDialogueWard> noDialogueWards,
        @Schema(description = "집계 시각(+09:00)") OffsetDateTime generatedAt) {

    public record MoodDistribution(
            @Schema(description = "기준 어르신 수(오늘 마친 대화가 있는 어르신)", example = "33") int wardCount,
            int joy, int calm, int worry, int depressed,
            @Schema(description = "판단할 수 없음(기분 null) - 분포 막대에 넣지 않는다") int unknown) {
    }

    public record ContinuingWard(String wardId, String name,
                                 @Schema(description = "WORRY 또는 DEPRESSED") String mood,
                                 @Schema(description = "이어진 일수(3 이상)") int days) {
    }

    public record NoDialogueWard(String wardId, String name,
                                 @Schema(description = "마지막으로 대답한 대화가 끝난 시각. 한 번도 없으면 null")
                                 OffsetDateTime lastDialogueAt) {
    }
}

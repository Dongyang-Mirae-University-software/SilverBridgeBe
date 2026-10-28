package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 관리자 AI 통계 - 의료 챗봇 집계(2026-10-28). AI {@code GET /api/v1/chat/stats} 응답을 이 모양으로만 옮긴다(허용 목록) -
 * AI가 필드를 더해도 자동으로 나가지 않고, 상담 원문·사용자 ID가 들어갈 칸이 없다.
 *
 * <p>개수 칸은 AI가 null(모름)을 주면 null 그대로다 - 0으로 채우지 않는다.</p>
 */
@Schema(description = "AI 의료 챗봇 집계(개수만)")
public record AdminAiChatStatsResponse(
        @Schema(description = "조회 시작일(KST, 포함)", example = "2026-10-01") String from,
        @Schema(description = "조회 종료일(KST, 포함)", example = "2026-10-31") String to,
        @Schema(description = "상담(턴) 수") Long totalChats,
        @Schema(description = "상담한 서로 다른 사용자 수") Long users,
        @Schema(description = "역할별 상담 수") ByRole byRole,
        @Schema(description = "챗봇 분류별 상담 수") ByRoute byRoute,
        @Schema(description = "응급 안내(119)를 낸 상담 수 - 고위험 판정 기준") Long emergencyGuidance,
        @Schema(description = "챗봇 안에서 예약 생성까지 끝난 수(변경으로 새로 만든 예약 포함)") Long reservationCompleted,
        @Schema(description = "모델·예약 처리가 답을 만들지 못해 기본 안내로 대신 답한 수") Long fallbackReplies,
        @Schema(description = "기간 안 날짜별 상담 수(0건 날짜 포함)") List<Daily> daily,
        @Schema(description = "AI가 집계한 시각(KST, ISO-8601 +09:00)", example = "2026-10-28T11:03:27+09:00") String generatedAt) {

    @Schema(description = "역할별 상담 수. unknown = 역할이 기록되지 않은 상담(2026-10-28 이전 일부 기록)")
    public record ByRole(Long guardian, Long ward, Long unknown) {}

    @Schema(description = "분류별 상담 수. reservation = 병원 예약, symptom = 증상 상담, general = 일반 대화, unknown = 분류 전 처리 실패")
    public record ByRoute(Long reservation, Long symptom, Long general, Long unknown) {}

    @Schema(description = "날짜별 상담 수")
    public record Daily(String date, Long totalChats) {}
}

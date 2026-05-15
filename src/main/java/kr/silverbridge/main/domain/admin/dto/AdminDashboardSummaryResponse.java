package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "관리자 대시보드 통계 요약")
public record AdminDashboardSummaryResponse(

        @Schema(description = "전체 활성 사용자 수 (ADMIN 제외)", example = "1240")
        long totalUsers,

        @Schema(description = "전월 대비 사용자 증감률 (%, 소수점 1자리). baseline = 0 인 경우 null", example = "12.5", nullable = true)
        Double userChangeRatePct,

        @Schema(description = "활성 피보호자 수", example = "618")
        long activeWards,

        @Schema(description = "전월 대비 활성 피보호자 증감률 (%, 소수점 1자리). baseline = 0 인 경우 null", example = "8.3", nullable = true)
        Double wardChangeRatePct,

        @Schema(description = "오늘 발생한 이상감지 건수", example = "7")
        long anomalyToday,

        @Schema(description = "전일 대비 이상감지 증감 수 (오늘 - 어제)", example = "2")
        long anomalyChangeFromYesterday,

        @Schema(description = "전체 이상감지 누적 건수", example = "2138")
        long anomalyTotal,

        @Schema(description = "오늘 신규 발생 이상감지 건수 (anomalyToday 와 동일)", example = "7")
        long anomalyNewToday
) {
}
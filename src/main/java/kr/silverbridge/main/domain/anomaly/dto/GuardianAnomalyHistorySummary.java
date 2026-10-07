package kr.silverbridge.main.domain.anomaly.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 보호자 이상감지 이력 화면의 건수 요약. 이력 목록의 {@code type} 필터·페이지와 무관하게
 * 조회 범위(ACTIVE 연결 피보호자, wardId 지정 시 그 한 명) 전체를 센다.
 *
 * <p>{@code byType}은 세 유형을 항상 담는다. 낙상·흉기는 2026-10-06부터 라이브라 0이 실제로 센 값이다.</p>
 */
@Schema(description = "보호자 이상감지 이력 건수 요약 (type 필터와 무관, 조회 범위 전체 기준)")
public record GuardianAnomalyHistorySummary(

        @Schema(description = "전체 상황 수 (= byType 합 = type 없이 이력을 조회했을 때의 totalElements)", example = "12")
        long total,

        @Schema(description = "아직 아무도 응답하지 않은 상황 수 (PENDING)", example = "3")
        long pendingCount,

        @Schema(description = "보호자 응답이 동수로 갈려 다시 확인이 필요한 상황 수 (CONFLICTED)", example = "1")
        long conflictedCount,

        @Schema(description = "헤더 '확인 필요 N건'용 = pendingCount + conflictedCount (보호자가 확인할 일이 남은 상황 전체)",
                example = "4")
        long needsReviewCount,

        @Schema(description = "유형별 상황 수. FIRE(연기 포함)·FALL·WEAPON을 항상 모두 담는다")
        ByType byType
) {

    public static final GuardianAnomalyHistorySummary EMPTY =
            new GuardianAnomalyHistorySummary(0, 0, 0, 0, new ByType(0, 0, 0));

    public record ByType(
            @Schema(description = "화재(연기 포함)", example = "8") long fire,
            @Schema(description = "낙상", example = "3") long fall,
            @Schema(description = "흉기", example = "1") long weapon
    ) {
    }
}

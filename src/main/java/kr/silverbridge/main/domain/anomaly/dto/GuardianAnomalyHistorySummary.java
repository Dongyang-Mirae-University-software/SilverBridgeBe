package kr.silverbridge.main.domain.anomaly.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 보호자 이상감지 이력 화면의 건수 요약. 이력 목록의 {@code type} 필터·페이지와 무관하게
 * 조회 범위(ACTIVE 연결 피보호자, wardId 지정 시 그 한 명)를 센다.
 *
 * <p>{@code deletedCameraCount}를 뺀 값은 모두 <b>지금 등록된 카메라</b>의 상황만 센다(2026-10-25,
 * = {@code camera=EXISTING}). 삭제된 카메라의 상황은 영상이 없어 다시 확인할 수 없으므로 기본 집계에서 빠진다.</p>
 *
 * <p>{@code byType}은 세 유형을 항상 담는다. 낙상·흉기는 2026-10-06부터 라이브라 0이 실제로 센 값이다.</p>
 */
@Schema(description = "보호자 이상감지 이력 건수 요약 (type 필터와 무관. deletedCameraCount 외에는 지금 등록된 카메라 기준)")
public record GuardianAnomalyHistorySummary(

        @Schema(description = "지금 등록된 카메라의 상황 수 (= type 없이 camera=EXISTING으로 이력을 조회했을 때의 totalElements. "
                + "정상적으로는 byType 합과 같다)", example = "12")
        long total,

        @Schema(description = "아직 아무도 응답하지 않은 상황 수 (PENDING, 지금 등록된 카메라 기준)", example = "3")
        long pendingCount,

        @Schema(description = "보호자 응답이 동수로 갈려 다시 확인이 필요한 상황 수 (CONFLICTED, 지금 등록된 카메라 기준)",
                example = "1")
        long conflictedCount,

        @Schema(description = "헤더 '확인 필요 N건'용 = pendingCount + conflictedCount (보호자가 확인할 일이 남은 상황 전체)",
                example = "4")
        long needsReviewCount,

        @Schema(description = "유형별 상황 수(지금 등록된 카메라 기준). FIRE(연기 포함)·FALL·WEAPON을 항상 모두 담는다")
        ByType byType,

        @Schema(description = "삭제된 카메라의 상황 수 (= camera=DELETED로 이력을 조회했을 때의 totalElements, 유형 무관)",
                example = "2")
        long deletedCameraCount
) {

    public static final GuardianAnomalyHistorySummary EMPTY =
            new GuardianAnomalyHistorySummary(0, 0, 0, 0, new ByType(0, 0, 0), 0);

    public record ByType(
            @Schema(description = "화재(연기 포함)", example = "8") long fire,
            @Schema(description = "낙상", example = "3") long fall,
            @Schema(description = "흉기", example = "1") long weapon
    ) {
    }
}

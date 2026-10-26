package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/** 제휴 병원 관리 상단 카드 숫자(삭제된 병원 제외, 2026-10-26). */
@Schema(description = "제휴 병원 요약")
public record AdminHospitalSummaryResponse(
        @Schema(description = "제휴 병원 수", example = "46")
        long total,
        @Schema(description = "앱 예약 가능 병원 수", example = "44")
        long bookingAvailable,
        @Schema(description = "가장 최근 등록 시각(KST). 병원이 없거나 알 수 없으면 null", nullable = true)
        OffsetDateTime latestRegisteredAt
) {
}

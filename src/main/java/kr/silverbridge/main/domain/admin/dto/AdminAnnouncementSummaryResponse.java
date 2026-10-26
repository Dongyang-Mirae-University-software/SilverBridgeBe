package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자 공지사항 화면 탭 건수(2026-10-26). 검색어와 무관한 전체 기준이다 - 검색 결과 건수는 각 목록의 totalElements.
 */
@Schema(description = "관리자 공지사항 탭 건수")
public record AdminAnnouncementSummaryResponse(

        @Schema(description = "게시된 공지 수", example = "12")
        long publishedCount,

        @Schema(description = "임시저장 수", example = "3")
        long draftCount
) {
}

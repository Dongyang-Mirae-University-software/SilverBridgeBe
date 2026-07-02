package kr.silverbridge.main.domain.inquiry.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.global.response.PageResponse;

/**
 * 관리자 문의 목록 응답. 상단 탭 카운트(전체/대기/완료) + 현재 필터·검색 결과 페이지를 함께 담는다.
 *
 * <p>탭 카운트는 <b>필터·검색과 무관한 전역 카운트</b>다(탭 배지는 항상 전체 기준으로 표시).
 * 목록(page)만 카테고리/상태/검색어로 필터링된다.</p>
 */
@Schema(description = "문의 목록 응답 (관리자)")
public record AdminInquiryListResponse(

        @Schema(description = "전체 문의 수", example = "137")
        long totalCount,

        @Schema(description = "답변 대기 수", example = "12")
        long waitingCount,

        @Schema(description = "답변 완료 수", example = "125")
        long answeredCount,

        @Schema(description = "현재 필터·검색·페이징이 적용된 문의 목록")
        PageResponse<AdminInquiryResponse> inquiries
) {
    public static AdminInquiryListResponse of(long totalCount, long waitingCount, long answeredCount,
                                              PageResponse<AdminInquiryResponse> inquiries) {
        return new AdminInquiryListResponse(totalCount, waitingCount, answeredCount, inquiries);
    }
}

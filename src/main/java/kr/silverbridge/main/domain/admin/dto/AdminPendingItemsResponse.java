package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "관리자 대시보드 처리 대기 현황")
public record AdminPendingItemsResponse(

        @Schema(description = "수락/거절되지 않은 피보호자 연결 요청 건수", example = "5")
        long pendingConnections,

        @Schema(description = "임시저장된 공지 건수", example = "3")
        long announcementDrafts,

        @Schema(description = "미확인 고객 문의 건수 (문의 기능 추가 전이므로 항상 0)", example = "0")
        long unreadInquiries
) {
}
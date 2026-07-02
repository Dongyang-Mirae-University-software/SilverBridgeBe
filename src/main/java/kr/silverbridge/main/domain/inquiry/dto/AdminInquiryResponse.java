package kr.silverbridge.main.domain.inquiry.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.inquiry.entity.Inquiry;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.enums.InquiryCategory;
import kr.silverbridge.main.global.enums.InquiryStatus;

import java.time.OffsetDateTime;

/**
 * 관리자 목록용 문의 응답(목록 행). 목록에는 content/answer 본문을 싣지 않는다(상세에서 제공).
 * 작성자명은 서비스에서 배치 조회해 주입한다(탈퇴 시 null).
 */
@Schema(description = "문의 응답 (관리자 목록)")
public record AdminInquiryResponse(

        @Schema(description = "문의 ID", example = "1")
        Long id,

        @Schema(description = "카테고리 코드", example = "SERVICE")
        InquiryCategory category,

        @Schema(description = "제목", example = "이상감지 알림이 오지 않아요")
        String title,

        @Schema(description = "작성자 ID (보호자)", example = "aB3x9Z")
        String authorId,

        @Schema(description = "작성자 이름 (탈퇴 시 null)", example = "김보호", nullable = true)
        String authorName,

        @Schema(description = "상태 코드", example = "WAITING")
        InquiryStatus status,

        @Schema(description = "작성 일시", example = "2026-07-01T10:00:00+09:00")
        OffsetDateTime createdAt
) {
    public static AdminInquiryResponse of(Inquiry inquiry, User author) {
        return new AdminInquiryResponse(
                inquiry.getId(),
                inquiry.getCategory(),
                inquiry.getTitle(),
                inquiry.getUserId(),
                author != null ? author.getName() : null,
                inquiry.getStatus(),
                inquiry.getCreatedAt()
        );
    }
}

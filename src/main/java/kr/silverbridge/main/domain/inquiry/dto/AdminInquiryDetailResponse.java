package kr.silverbridge.main.domain.inquiry.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.inquiry.entity.Inquiry;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.enums.InquiryCategory;
import kr.silverbridge.main.global.enums.InquiryStatus;

import java.time.OffsetDateTime;

/**
 * 관리자 상세용 문의 응답. 답변 모달에서 문의 본문 + 기존 답변을 함께 표시한다.
 * 작성자명·답변자명은 서비스에서 배치 조회해 주입한다(탈퇴 시 null).
 */
@Schema(description = "문의 응답 (관리자 상세)")
public record AdminInquiryDetailResponse(

        @Schema(description = "문의 ID", example = "1")
        Long id,

        @Schema(description = "카테고리 코드", example = "SERVICE")
        InquiryCategory category,

        @Schema(description = "제목", example = "이상감지 알림이 오지 않아요")
        String title,

        @Schema(description = "내용", example = "어제부터 피보호자 이상감지 알림이 전혀 오지 않습니다.")
        String content,

        @Schema(description = "작성자 ID (보호자)", example = "aB3x9Z")
        String authorId,

        @Schema(description = "작성자 이름 (탈퇴 시 null)", example = "김보호", nullable = true)
        String authorName,

        @Schema(description = "상태 코드", example = "WAITING")
        InquiryStatus status,

        @Schema(description = "관리자 답변 (답변 전 null)", nullable = true)
        String answer,

        @Schema(description = "답변 관리자 이름 (답변 전 null)", nullable = true)
        String answeredByName,

        @Schema(description = "답변 일시 (답변 전 null)", nullable = true)
        OffsetDateTime answeredAt,

        @Schema(description = "작성 일시", example = "2026-07-01T10:00:00+09:00")
        OffsetDateTime createdAt
) {
    public static AdminInquiryDetailResponse of(Inquiry inquiry, User author, User answeredBy) {
        return new AdminInquiryDetailResponse(
                inquiry.getId(),
                inquiry.getCategory(),
                inquiry.getTitle(),
                inquiry.getContent(),
                inquiry.getUserId(),
                author != null ? author.getName() : null,
                inquiry.getStatus(),
                inquiry.getAnswer(),
                answeredBy != null ? answeredBy.getName() : null,
                inquiry.getAnsweredAt(),
                inquiry.getCreatedAt()
        );
    }
}

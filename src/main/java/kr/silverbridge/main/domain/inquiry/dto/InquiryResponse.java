package kr.silverbridge.main.domain.inquiry.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.inquiry.entity.Inquiry;
import kr.silverbridge.main.global.enums.InquiryCategory;
import kr.silverbridge.main.global.enums.InquiryStatus;

import java.time.OffsetDateTime;

/**
 * 보호자용 문의 응답. 목록·상세 공용.
 *
 * <p>답변 전(WAITING)에는 answer/answeredAt이 null. 답변 완료(ANSWERED) 건은 관리자 답변을 함께 내려준다.
 * 카테고리/상태 한글 표시명은 프론트가 코드값으로 매핑한다(서버는 enum 코드값만 반환).</p>
 */
@Schema(description = "문의 응답 (보호자)")
public record InquiryResponse(

        @Schema(description = "문의 ID", example = "1")
        Long id,

        @Schema(description = "카테고리 코드", example = "SERVICE")
        InquiryCategory category,

        @Schema(description = "제목", example = "이상감지 알림이 오지 않아요")
        String title,

        @Schema(description = "내용", example = "어제부터 피보호자 이상감지 알림이 전혀 오지 않습니다.")
        String content,

        @Schema(description = "상태 코드", example = "WAITING")
        InquiryStatus status,

        @Schema(description = "관리자 답변 (답변 전 null)", nullable = true)
        String answer,

        @Schema(description = "답변 일시 (답변 전 null)", nullable = true)
        OffsetDateTime answeredAt,

        @Schema(description = "작성 일시", example = "2026-07-01T10:00:00+09:00")
        OffsetDateTime createdAt
) {
    public static InquiryResponse of(Inquiry inquiry) {
        return new InquiryResponse(
                inquiry.getId(),
                inquiry.getCategory(),
                inquiry.getTitle(),
                inquiry.getContent(),
                inquiry.getStatus(),
                inquiry.getAnswer(),
                inquiry.getAnsweredAt(),
                inquiry.getCreatedAt()
        );
    }
}

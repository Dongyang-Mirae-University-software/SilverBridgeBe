package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.announcement.entity.AnnouncementDraft;
import kr.silverbridge.main.domain.user.entity.User;

import java.time.OffsetDateTime;

@Schema(description = "공지 임시저장 응답")
public record AdminAnnouncementDraftResponse(

        @Schema(description = "임시저장 ID", example = "1")
        Long id,

        @Schema(description = "작성자 ID (탈퇴 시 null)", example = "aB3x9Z", nullable = true)
        String authorId,

        @Schema(description = "작성자 이름 (탈퇴 시 null)", example = "관리자", nullable = true)
        String authorName,

        @Schema(description = "제목 (비어 있을 수 있음)", example = "서비스 점검 안내")
        String title,

        @Schema(description = "내용 (비어 있을 수 있음)", example = "2025년 5월 1일 ...")
        String content,

        @Schema(description = "임시저장 생성 일시", example = "2025-06-01T08:00:00+09:00")
        OffsetDateTime createdAt,

        @Schema(description = "마지막 수정 일시", example = "2025-06-01T08:30:00+09:00")
        OffsetDateTime updatedAt
) {

    public static AdminAnnouncementDraftResponse of(AnnouncementDraft draft, User author) {
        return new AdminAnnouncementDraftResponse(
                draft.getId(),
                author != null ? author.getId() : draft.getAuthorId(),
                author != null ? author.getName() : null,
                draft.getTitle(),
                draft.getContent(),
                draft.getCreatedAt(),
                draft.getUpdatedAt()
        );
    }
}

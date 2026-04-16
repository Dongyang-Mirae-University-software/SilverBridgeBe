package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.announcement.entity.Announcement;
import kr.silverbridge.main.domain.user.entity.User;

import java.time.OffsetDateTime;

@Schema(description = "공지 응답")
public record AnnouncementResponse(

        @Schema(description = "공지 ID", example = "1")
        Long id,

        @Schema(description = "작성자 ID (탈퇴 시 null)", example = "aB3x9Z", nullable = true)
        String authorId,

        @Schema(description = "작성자 이름 (탈퇴 시 null)", example = "관리자", nullable = true)
        String authorName,

        @Schema(description = "제목", example = "서비스 점검 안내")
        String title,

        @Schema(description = "내용", example = "2025년 5월 1일 오전 2시부터 4시까지 서버 점검이 예정되어 있습니다.")
        String content,

        @Schema(description = "생성 일시", example = "2025-06-01T08:00:00+09:00")
        OffsetDateTime createdAt,

        @Schema(description = "수정 일시", example = "2025-06-01T08:30:00+09:00")
        OffsetDateTime updatedAt
) {

    public static AnnouncementResponse of(Announcement announcement, User author) {
        return new AnnouncementResponse(
                announcement.getId(),
                author != null ? author.getId() : announcement.getAuthorId(),
                author != null ? author.getName() : null,
                announcement.getTitle(),
                announcement.getContent(),
                announcement.getCreatedAt(),
                announcement.getUpdatedAt()
        );
    }
}

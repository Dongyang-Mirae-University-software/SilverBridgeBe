package kr.silverbridge.main.domain.announcement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.announcement.entity.Announcement;
import kr.silverbridge.main.domain.user.entity.User;

import java.time.OffsetDateTime;

/**
 * 일반 사용자용 공지 응답
 * 관리자용 AnnouncementResponse와 달리 authorId는 노출하지 않는다 (관리자 계정 노출 방지).
 */
@Schema(description = "공지 응답 (일반 사용자용)")
public record AnnouncementResponse(

        @Schema(description = "공지 ID", example = "1")
        Long id,

        @Schema(description = "작성자 이름 (탈퇴 시 null)", example = "관리자", nullable = true)
        String authorName,

        @Schema(description = "제목", example = "서비스 점검 안내")
        String title,

        @Schema(description = "내용", example = "2025년 5월 1일 오전 2시부터 4시까지 서버 점검이 예정되어 있습니다.")
        String content,

        @Schema(description = "작성 일시", example = "2025-06-01T08:00:00+09:00")
        OffsetDateTime createdAt,

        @Schema(description = "마지막 수정 일시", example = "2025-06-01T08:30:00+09:00")
        OffsetDateTime updatedAt
) {

    public static AnnouncementResponse of(Announcement announcement, User author) {
        return new AnnouncementResponse(
                announcement.getId(),
                author != null ? author.getName() : null,
                announcement.getTitle(),
                announcement.getContent(),
                announcement.getCreatedAt(),
                announcement.getUpdatedAt()
        );
    }
}

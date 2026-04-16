package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.admin.entity.AdminAuditLog;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.enums.AdminAuditAction;

import java.time.OffsetDateTime;

@Schema(description = "관리자 행동 감사 로그 응답")
public record AdminAuditLogResponse(

        @Schema(description = "로그 ID", example = "1")
        Long id,

        @Schema(description = "행동한 관리자 ID", example = "aB3x9Z")
        String adminId,

        @Schema(description = "관리자 이름 (탈퇴 시 null)", example = "관리자", nullable = true)
        String adminName,

        @Schema(description = "행동 유형",
                allowableValues = {
                        "USER_STATUS_CHANGE", "USER_ROLE_CHANGE", "USER_FORCE_DELETE",
                        "FORCE_CONNECT", "FORCE_DISCONNECT",
                        "ANNOUNCEMENT_CREATE", "ANNOUNCEMENT_UPDATE", "ANNOUNCEMENT_PUBLISH", "ANNOUNCEMENT_DELETE"
                },
                example = "USER_STATUS_CHANGE")
        AdminAuditAction action,

        @Schema(description = "대상 ID (userId / connectionId / announcementId)", example = "aB3x9Z", nullable = true)
        String targetId,

        @Schema(description = "변경 내용 요약", example = "상태 변경: ACTIVE → INACTIVE", nullable = true)
        String detail,

        @Schema(description = "발생 일시", example = "2025-06-01T09:00:00+09:00")
        OffsetDateTime createdAt
) {

    public static AdminAuditLogResponse of(AdminAuditLog log, User admin) {
        return new AdminAuditLogResponse(
                log.getId(),
                log.getAdminId(),
                admin != null ? admin.getName() : null,
                log.getAction(),
                log.getTargetId(),
                log.getDetail(),
                log.getCreatedAt()
        );
    }
}

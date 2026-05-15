package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.enums.Role;

import java.time.OffsetDateTime;

@Schema(description = "관리자 대시보드 최근 가입 회원")
public record AdminRecentUserResponse(

        @Schema(description = "회원 ID", example = "aB3x9Z")
        String id,

        @Schema(description = "이름", example = "홍길동")
        String name,

        @Schema(description = "역할 (WARD / GUARDIAN)", example = "WARD")
        Role role,

        @Schema(description = "가입 일시", example = "2026-05-10T14:23:11+09:00")
        OffsetDateTime createdAt
) {

    public static AdminRecentUserResponse from(User user) {
        return new AdminRecentUserResponse(
                user.getId(),
                user.getName(),
                user.getRole(),
                user.getCreatedAt()
        );
    }
}

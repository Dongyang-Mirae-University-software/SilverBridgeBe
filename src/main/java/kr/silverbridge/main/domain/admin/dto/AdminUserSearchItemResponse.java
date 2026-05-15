package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;

@Schema(description = "관리자 회원관리 검색 항목")
public record AdminUserSearchItemResponse(

        @Schema(description = "회원 ID (6자리)", example = "aB3x9Z")
        String id,

        @Schema(description = "이메일", example = "user@example.com")
        String email,

        @Schema(description = "이름", example = "홍길동")
        String name,

        @Schema(description = "역할", allowableValues = {"WARD", "GUARDIAN", "ADMIN"}, example = "WARD")
        Role role,

        @Schema(description = "전화번호 (관리자 화면 — 원본 그대로 노출)", example = "010-1234-5678", nullable = true)
        String phone,

        @Schema(description = "계정 상태", allowableValues = {"ACTIVE", "INACTIVE"}, example = "ACTIVE")
        Status status
) {

    public static AdminUserSearchItemResponse from(User user) {
        return new AdminUserSearchItemResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole(),
                user.getPhone(),
                user.getStatus()
        );
    }
}

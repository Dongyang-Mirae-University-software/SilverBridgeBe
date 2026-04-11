package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.user.entity.User;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
@Schema(description = "사용자 목록 항목")
public class UserSummaryResponse {

    @Schema(description = "사용자 UUID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    private String userId;

    @Schema(description = "이메일", example = "user@example.com")
    private String email;

    @Schema(description = "이름", example = "홍길동")
    private String name;

    @Schema(description = "역할", allowableValues = {"WARD", "GUARDIAN"}, example = "WARD")
    private String role;

    @Schema(description = "계정 상태", allowableValues = {"ACTIVE", "INACTIVE"}, example = "ACTIVE")
    private String status;

    @Schema(description = "가입 경로", allowableValues = {"LOCAL", "KAKAO"}, example = "LOCAL")
    private String provider;

    @Schema(description = "이메일 인증 여부", example = "true")
    private boolean emailVerified;

    @Schema(description = "마지막 로그인 일시 (없으면 null)", nullable = true)
    private OffsetDateTime lastLoginAt;

    @Schema(description = "가입 일시")
    private OffsetDateTime createdAt;

    public static UserSummaryResponse from(User user) {
        return UserSummaryResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .role(user.getRole().name())
                .status(user.getStatus().name())
                .provider(user.getProvider().name())
                .emailVerified(user.isEmailVerified())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .build();
    }
}

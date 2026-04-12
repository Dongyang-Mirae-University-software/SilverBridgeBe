package kr.silverbridge.main.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.user.entity.User;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
@Schema(description = "내 프로필 응답")
public class UserProfileResponse {

    @Schema(description = "사용자 UUID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    private String id;

    @Schema(description = "이메일", example = "user@example.com")
    private String email;

    @Schema(description = "이름", example = "홍길동")
    private String name;

    @Schema(description = "전화번호 (없으면 null)", example = "01012345678", nullable = true)
    private String phone;

    @Schema(description = "가입 경로", allowableValues = {"LOCAL", "KAKAO"}, example = "LOCAL")
    private String provider;

    @Schema(description = "역할. WARD: 피보호자, GUARDIAN: 보호자, ADMIN: 관리자", allowableValues = {"WARD", "GUARDIAN", "ADMIN"}, example = "WARD")
    private String role;

    @Schema(description = "마지막 로그인 일시 (없으면 null)", example = "2025-01-01T09:00:00+09:00", nullable = true)
    private OffsetDateTime lastLoginAt;

    @Schema(description = "가입 일시", example = "2025-01-01T09:00:00+09:00")
    private OffsetDateTime createdAt;

    public static UserProfileResponse from(User user) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .phone(user.getPhone())
                .provider(user.getProvider().name())
                .role(user.getRole().name())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .build();
    }
}

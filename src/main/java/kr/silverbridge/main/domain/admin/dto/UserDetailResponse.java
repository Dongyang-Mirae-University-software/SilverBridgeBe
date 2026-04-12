package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.user.entity.User;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
@Schema(description = "사용자 상세 정보")
public class UserDetailResponse {

    @Schema(description = "사용자 UUID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    private String userId;

    @Schema(description = "이메일", example = "user@example.com")
    private String email;

    @Schema(description = "이름", example = "홍길동")
    private String name;

    @Schema(description = "전화번호 (없으면 null)", example = "01012345678", nullable = true)
    private String phone;

    @Schema(description = "역할", allowableValues = {"WARD", "GUARDIAN", "ADMIN"}, example = "WARD")
    private String role;

    @Schema(description = "계정 상태", allowableValues = {"ACTIVE", "INACTIVE"}, example = "ACTIVE")
    private String status;

    @Schema(description = "가입 경로", allowableValues = {"LOCAL", "KAKAO"}, example = "LOCAL")
    private String provider;

    @Schema(description = "카카오 provider ID (카카오 가입자만 존재, 없으면 null)", example = "3456789012", nullable = true)
    private String providerId;

    @Schema(description = "프로필 이미지 URL (없으면 null)", example = "https://file.silverbridge.kr/profiles/uuid.jpg", nullable = true)
    private String profileImage;

    @Schema(description = "마지막 로그인 일시 (없으면 null)", example = "2025-01-01T09:00:00+09:00", nullable = true)
    private OffsetDateTime lastLoginAt;

    @Schema(description = "가입 일시", example = "2025-01-01T09:00:00+09:00")
    private OffsetDateTime createdAt;

    @Schema(description = "정보 수정 일시", example = "2025-06-01T12:00:00+09:00")
    private OffsetDateTime updatedAt;

    public static UserDetailResponse from(User user) {
        return UserDetailResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .phone(user.getPhone())
                .role(user.getRole().name())
                .status(user.getStatus().name())
                .provider(user.getProvider().name())
                .providerId(user.getProviderId())
                .profileImage(user.getProfileImage())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}

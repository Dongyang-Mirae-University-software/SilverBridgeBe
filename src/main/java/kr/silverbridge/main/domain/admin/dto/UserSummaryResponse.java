package kr.silverbridge.main.domain.admin.dto;

import kr.silverbridge.main.domain.user.entity.User;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
@Getter
@Builder
public class UserSummaryResponse {

    private String userId;
    private String email;
    private String name;
    private String role;
    private String status;
    private String provider;
    private boolean emailVerified;
    private OffsetDateTime lastLoginAt;
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

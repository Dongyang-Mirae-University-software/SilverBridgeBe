package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.user.entity.User;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = "로그인 응답")
public class LoginResponse {

    @Schema(description = "API 호출 시 Authorization 헤더에 담을 토큰. 'Bearer {accessToken}' 형식으로 사용. 유효 시간: 1시간", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String accessToken;

    @Schema(description = "Access Token 만료 시 재발급에 사용하는 토큰. POST /api/auth/refresh 에 전달. 유효 시간: 14일", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String refreshToken;

    @Schema(description = "사용자 고유 ID (UUID)", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    private String userId;

    @Schema(description = "사용자 이메일", example = "user@example.com")
    private String email;

    @Schema(description = "사용자 이름", example = "홍길동")
    private String name;

    @Schema(description = "사용자 역할. WARD: 피보호자, GUARDIAN: 보호자, ADMIN: 관리자", example = "WARD", allowableValues = {"WARD", "GUARDIAN", "ADMIN"})
    private String role;

    public static LoginResponse of(User user, String accessToken, String refreshToken) {
        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .userId(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .role(user.getRole().name())
                .build();
    }
}

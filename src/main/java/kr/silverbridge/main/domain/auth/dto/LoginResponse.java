package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.user.entity.User;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = "로그인 응답")
public class LoginResponse {

    @Schema(description = "API 호출 시 Authorization 헤더에 담을 토큰. 'Bearer {accessToken}' 형식으로 사용. 유효 시간: 30분", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String accessToken;

    @Schema(description = "[전환 기간 전용, 곧 제거] 같은 값이 HttpOnly 쿠키(Set-Cookie)로 내려간다. 화면 코드는 읽거나 저장하지 말 것. 쿠키 전용 전환이 끝나면 null이 된다", example = "eyJhbGciOiJIUzI1NiJ9...", nullable = true)
    private String refreshToken;

    @Schema(description = "사용자 고유 ID (6자 영숫자)", example = "aB3x9Z")
    private String userId;

    @Schema(description = "사용자 이메일", example = "user@example.com")
    private String email;

    @Schema(description = "사용자 이름", example = "홍길동")
    private String name;

    @Schema(description = "사용자 역할. WARD: 피보호자, GUARDIAN: 보호자, ADMIN: 관리자", example = "WARD", allowableValues = {"WARD", "GUARDIAN", "ADMIN"})
    private String role;

    /** 본문의 refreshToken만 바꾼 사본 (쿠키 전용 전환 시 null로 비운다, JSON 키는 유지). */
    public LoginResponse withRefreshToken(String token) {
        return LoginResponse.builder().accessToken(accessToken).refreshToken(token)
                .userId(userId).email(email).name(name).role(role).build();
    }

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

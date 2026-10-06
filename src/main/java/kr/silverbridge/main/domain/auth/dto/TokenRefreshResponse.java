package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Schema(description = "Access Token 재발급 응답 (Refresh Token Rotation 적용 — 기존 refreshToken은 즉시 무효화)")
public class TokenRefreshResponse {

    @Schema(description = "새로 발급된 Access Token. Authorization 헤더에 'Bearer {accessToken}' 형식으로 사용. 유효 시간: 30분", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String accessToken;

    @Schema(description = "[전환 기간 전용, 곧 제거] 새 Refresh Token은 HttpOnly 쿠키(Set-Cookie)로 자동 교체된다. 화면 코드는 읽거나 저장하지 말 것. 쿠키 전용 전환이 끝나면 null이 된다. 유효 시간: 7일", nullable = true, example = "eyJhbGciOiJIUzI1NiJ9...")
    private String refreshToken;
}

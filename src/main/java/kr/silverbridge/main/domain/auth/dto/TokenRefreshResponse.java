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

    @Schema(description = "새로 발급된 Refresh Token. 기존 refreshToken은 무효화되므로 반드시 이 값으로 교체하여 저장. 유효 시간: 7일", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String refreshToken;
}

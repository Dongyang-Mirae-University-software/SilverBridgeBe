package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
@Schema(description = "Access Token 재발급 요청")
public class TokenRefreshRequest {

    @Schema(description = "로그인 시 발급된 Refresh Token", example = "eyJhbGciOiJIUzI1NiJ9...")
    @NotBlank(message = "리프레시 토큰을 입력해주세요.")
    private String refreshToken;
}

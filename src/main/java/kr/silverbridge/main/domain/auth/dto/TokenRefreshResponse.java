package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Schema(description = "Access Token 재발급 응답")
public class TokenRefreshResponse {

    @Schema(description = "새로 발급된 Access Token. Authorization 헤더에 'Bearer {accessToken}' 형식으로 사용", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String accessToken;
}

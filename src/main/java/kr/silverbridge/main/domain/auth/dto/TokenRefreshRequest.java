package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@Schema(description = "Access Token 재발급 요청. 본문은 비워도 된다(HttpOnly 쿠키의 refresh 토큰을 사용)")
public class TokenRefreshRequest {

    @Schema(description = "[전환 기간 전용, 곧 제거] 쿠키가 있으면 보내지 않는다. 본문과 쿠키가 모두 있으면 본문이 우선", example = "eyJhbGciOiJIUzI1NiJ9...", nullable = true)
    private String refreshToken;
}

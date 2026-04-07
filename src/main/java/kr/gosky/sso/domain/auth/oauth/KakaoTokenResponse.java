package kr.gosky.sso.domain.auth.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

// 카카오 토큰 발급 API 응답 DTO
@Getter
public class KakaoTokenResponse {

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("token_type")
    private String tokenType;

    @JsonProperty("refresh_token")
    private String refreshToken;

    @JsonProperty("expires_in")
    private Long expiresIn;

    @JsonProperty("refresh_token_expires_in")
    private Long refreshTokenExpiresIn;
}

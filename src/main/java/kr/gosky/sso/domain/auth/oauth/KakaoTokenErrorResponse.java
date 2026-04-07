package kr.gosky.sso.domain.auth.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

// 카카오 토큰 발급 에러 응답 DTO (KOE 에러 코드)
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class KakaoTokenErrorResponse {

    private String error;

    @JsonProperty("error_description")
    private String errorDescription;

    @JsonProperty("error_code")
    private String errorCode;
}

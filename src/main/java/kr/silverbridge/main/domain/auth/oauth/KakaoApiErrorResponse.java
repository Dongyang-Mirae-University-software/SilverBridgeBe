package kr.silverbridge.main.domain.auth.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;

// 카카오 공통 API 에러 응답 DTO (숫자 에러 코드)
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class KakaoApiErrorResponse {

    private Integer code;
    private String msg;
}

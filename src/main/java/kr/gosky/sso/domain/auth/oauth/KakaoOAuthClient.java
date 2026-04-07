package kr.gosky.sso.domain.auth.oauth;

import kr.gosky.sso.global.exception.CustomException;
import kr.gosky.sso.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;

// 카카오 OAuth REST API 호출 클라이언트
@Slf4j
@Component
public class KakaoOAuthClient {

    @Value("${kakao.rest-api-key}")
    private String restApiKey;

    @Value("${kakao.redirect-uri}")
    private String redirectUri;

    private static final String TOKEN_URL    = "https://kauth.kakao.com/oauth/token";
    private static final String USER_INFO_URL = "https://kapi.kakao.com/v2/user/me";

    private final RestClient restClient = RestClient.create();

    // 인가 코드 → 카카오 액세스 토큰 교환
    public KakaoTokenResponse getToken(String code) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("client_id", restApiKey);
        params.add("redirect_uri", redirectUri);
        params.add("code", code);

        try {
            return restClient.post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(params)
                    .retrieve()
                    .body(KakaoTokenResponse.class);
        } catch (RestClientResponseException e) {
            log.error("카카오 토큰 발급 실패: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new CustomException(ErrorCode.KAKAO_AUTH_ERROR);
        } catch (RestClientException e) {
            log.error("카카오 토큰 발급 실패 (네트워크 오류): {}", e.getMessage());
            throw new CustomException(ErrorCode.KAKAO_AUTH_ERROR);
        }
    }

    // 카카오 액세스 토큰 → 사용자 정보 조회
    public KakaoUserInfoResponse getUserInfo(String kakaoAccessToken) {
        try {
            return restClient.get()
                    .uri(USER_INFO_URL)
                    .header("Authorization", "Bearer " + kakaoAccessToken)
                    .retrieve()
                    .body(KakaoUserInfoResponse.class);
        } catch (RestClientResponseException e) {
            log.error("카카오 사용자 정보 조회 실패: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new CustomException(ErrorCode.KAKAO_AUTH_ERROR);
        } catch (RestClientException e) {
            log.error("카카오 사용자 정보 조회 실패 (네트워크 오류): {}", e.getMessage());
            throw new CustomException(ErrorCode.KAKAO_AUTH_ERROR);
        }
    }
}

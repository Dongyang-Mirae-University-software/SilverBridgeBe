package kr.gosky.sso.domain.auth.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.gosky.sso.global.exception.CustomException;
import kr.gosky.sso.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class KakaoOAuthClient {

    private final ObjectMapper objectMapper;

    @Value("${kakao.rest-api-key}")
    private String restApiKey;

    @Value("${kakao.redirect-uri}")
    private String redirectUri;

    private static final String TOKEN_URL     = "https://kauth.kakao.com/oauth/token";
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
            String body = e.getResponseBodyAsString();
            log.error("카카오 토큰 발급 실패: status={}, body={}", e.getStatusCode(), body);
            throw new CustomException(resolveTokenError(body));
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
            String body = e.getResponseBodyAsString();
            log.error("카카오 사용자 정보 조회 실패: status={}, body={}", e.getStatusCode(), body);
            throw new CustomException(resolveApiError(body));
        } catch (RestClientException e) {
            log.error("카카오 사용자 정보 조회 실패 (네트워크 오류): {}", e.getMessage());
            throw new CustomException(ErrorCode.KAKAO_AUTH_ERROR);
        }
    }

    // 카카오 토큰 엔드포인트 에러 코드 매핑 (KOE 코드)
    // https://developers.kakao.com/docs/latest/ko/rest-api/error-code
    private ErrorCode resolveTokenError(String body) {
        try {
            KakaoTokenErrorResponse error = objectMapper.readValue(body, KakaoTokenErrorResponse.class);
            String errorCode = error.getErrorCode();
            if (errorCode == null) return ErrorCode.KAKAO_AUTH_ERROR;
            return switch (errorCode) {
                case "KOE320", "KOE321" -> ErrorCode.KAKAO_INVALID_CODE;  // 만료 또는 이미 사용된 코드
                default -> ErrorCode.KAKAO_AUTH_ERROR;
            };
        } catch (Exception e) {
            return ErrorCode.KAKAO_AUTH_ERROR;
        }
    }

    // 카카오 공통 API 에러 코드 매핑 (숫자 코드)
    // https://developers.kakao.com/docs/latest/ko/rest-api/error-code
    private ErrorCode resolveApiError(String body) {
        try {
            KakaoApiErrorResponse error = objectMapper.readValue(body, KakaoApiErrorResponse.class);
            Integer code = error.getCode();
            if (code == null) return ErrorCode.KAKAO_AUTH_ERROR;
            return switch (code) {
                case -401 -> ErrorCode.KAKAO_INVALID_CODE;      // 유효하지 않은 액세스 토큰
                case -402 -> ErrorCode.KAKAO_PERMISSION_DENIED; // 사용자 동의 필요
                case -103 -> ErrorCode.KAKAO_DORMANT_ACCOUNT;   // 휴면/존재하지 않는 계정
                default   -> ErrorCode.KAKAO_AUTH_ERROR;
            };
        } catch (Exception e) {
            return ErrorCode.KAKAO_AUTH_ERROR;
        }
    }
}

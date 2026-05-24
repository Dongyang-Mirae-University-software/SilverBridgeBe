package kr.silverbridge.main.domain.auth.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

// 카카오 OAuth REST API 호출 클라이언트
@Slf4j
@Component
public class KakaoOAuthClient {

    @Value("${kakao.rest-api-key}")
    private String restApiKey;

    // 카카오 콘솔 [보안 > Client Secret] 발급 값 — 토큰 교환 시 함께 전송해 인가코드 탈취 시 토큰 발급을 차단
    @Value("${kakao.client-secret}")
    private String clientSecret;

    private static final String TOKEN_URL     = "https://kauth.kakao.com/oauth/token";
    private static final String USER_INFO_URL = "https://kapi.kakao.com/v2/user/me";

    // 카카오 서버 응답 지연 시 Tomcat connection thread가 무한 점유되지 않도록 timeout 명시 (H-A8)
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT    = Duration.ofSeconds(5);

    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public KakaoOAuthClient(ObjectMapper objectMapper) {
        // JacksonConfig 빈 사용 — 안전 설정·모듈 자동 등록 공유 (M-M1)
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .requestFactory(buildRequestFactory())
                .build();
    }

    private static ClientHttpRequestFactory buildRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return factory;
    }

    // 인가 코드 → 카카오 액세스 토큰 교환
    public KakaoTokenResponse getToken(String code, String redirectUri) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("client_id", restApiKey);
        params.add("client_secret", clientSecret);
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
            String errorCode = extractTokenErrorCode(e.getResponseBodyAsString());
            // 응답 body 전체 노출 차단 (M-M3) — 토큰 응답에 access_token이 포함되는 경우의 PII 누출 방지.
            // 카카오 응답 포맷상 errorCode 만 분기·디버깅에 의미 있음
            log.error("카카오 토큰 발급 실패: status={}, errorCode={}", e.getStatusCode(), errorCode);
            throw new CustomException(mapTokenError(errorCode));
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
            Integer apiCode = extractApiErrorCode(e.getResponseBodyAsString());
            log.error("카카오 사용자 정보 조회 실패: status={}, code={}", e.getStatusCode(), apiCode);
            throw new CustomException(mapApiError(apiCode));
        } catch (RestClientException e) {
            log.error("카카오 사용자 정보 조회 실패 (네트워크 오류): {}", e.getMessage());
            throw new CustomException(ErrorCode.KAKAO_AUTH_ERROR);
        }
    }

    // 카카오 토큰 엔드포인트 에러 코드 추출 (KOE 코드)
    private String extractTokenErrorCode(String body) {
        try {
            return objectMapper.readValue(body, KakaoTokenErrorResponse.class).getErrorCode();
        } catch (Exception e) {
            return null;
        }
    }

    // 카카오 공통 API 에러 코드 추출 (숫자 코드)
    private Integer extractApiErrorCode(String body) {
        try {
            return objectMapper.readValue(body, KakaoApiErrorResponse.class).getCode();
        } catch (Exception e) {
            return null;
        }
    }

    // KOE 코드 → ErrorCode 매핑
    // https://developers.kakao.com/docs/latest/ko/rest-api/error-code
    private static ErrorCode mapTokenError(String errorCode) {
        if (errorCode == null) return ErrorCode.KAKAO_AUTH_ERROR;
        return switch (errorCode) {
            case "KOE320", "KOE321" -> ErrorCode.KAKAO_INVALID_CODE;  // 만료 또는 이미 사용된 코드
            default -> ErrorCode.KAKAO_AUTH_ERROR;
        };
    }

    // 카카오 API 숫자 에러 코드 → ErrorCode 매핑
    private static ErrorCode mapApiError(Integer code) {
        if (code == null) return ErrorCode.KAKAO_AUTH_ERROR;
        return switch (code) {
            case -401 -> ErrorCode.KAKAO_INVALID_CODE;      // 유효하지 않은 액세스 토큰
            case -402 -> ErrorCode.KAKAO_PERMISSION_DENIED; // 사용자 동의 필요
            case -103 -> ErrorCode.KAKAO_DORMANT_ACCOUNT;   // 휴면/존재하지 않는 계정
            default   -> ErrorCode.KAKAO_AUTH_ERROR;
        };
    }
}

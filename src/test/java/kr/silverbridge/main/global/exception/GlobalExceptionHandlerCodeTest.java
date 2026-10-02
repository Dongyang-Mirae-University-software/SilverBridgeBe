package kr.silverbridge.main.global.exception;

import kr.silverbridge.main.global.response.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 오류 응답 code(ErrorCode 이름), 파일 누락 400, NUL 입력 400, 429 retryAfter, 503 매핑 검증. */
class GlobalExceptionHandlerCodeTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("CustomException → ErrorCode 이름이 code 로 내려간다")
    void customException_code() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleCustomException(new CustomException(ErrorCode.CAMERA_NOT_AUTHORIZED));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().getCode()).isEqualTo("CAMERA_NOT_AUTHORIZED");
        assertThat(res.getBody().getMessage()).isEqualTo(ErrorCode.CAMERA_NOT_AUTHORIZED.getMessage());
    }

    @Test
    @DisplayName("AccessDenied(@PreAuthorize) → 403 + FORBIDDEN code")
    void accessDenied_code() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleAccessDenied(new AccessDeniedException("x"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().getCode()).isEqualTo("FORBIDDEN");
        assertThat(res.getBody().getMessage()).isEqualTo("접근 권한이 없습니다.");
    }

    @Test
    @DisplayName("성공 응답·문구만 쓰는 기존 fail 에는 code 가 없다(JSON 직렬화 시 생략)")
    void success_noCode() {
        assertThat(ApiResponse.ok("done").getCode()).isNull();
        assertThat(ApiResponse.fail("legacy").getCode()).isNull();
    }

    @Test
    @DisplayName("파일 파트 누락 → 400 '파일이 필요합니다'")
    void missingPart_400() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleMissingPart(new MissingServletRequestPartException("file"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getMessage()).isEqualTo("파일이 필요합니다.");
        assertThat(res.getBody().getCode()).isEqualTo("FILE_REQUIRED");
    }

    @Test
    @DisplayName("MultipartFile 파라미터 누락 → 400 '파일이 필요합니다', 일반 파라미터는 기존 문구")
    void missingParameter() {
        ResponseEntity<ApiResponse<Void>> file = handler.handleMissingParameter(
                new MissingServletRequestParameterException("file", "MultipartFile"));
        ResponseEntity<ApiResponse<Void>> plain = handler.handleMissingParameter(
                new MissingServletRequestParameterException("page", "int"));

        assertThat(file.getBody().getMessage()).isEqualTo("파일이 필요합니다.");
        assertThat(plain.getBody().getMessage()).isEqualTo("'page' 값이 필요합니다.");
        assertThat(plain.getBody().getCode()).isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("multipart 형식 아님(MultipartException) → 400")
    void multipart_400() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleMultipart(new MultipartException("Current request is not a multipart request"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getCode()).isEqualTo("FILE_REQUIRED");
    }

    @Test
    @DisplayName("NUL 입력(22021) → 500이 아니라 400 '잘못된 입력값'")
    void nul_22021_400() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException("x",
                new SQLException("invalid byte sequence for encoding \"UTF8\": 0x00", "22021"));

        ResponseEntity<ApiResponse<Void>> res = handler.handleDataIntegrityViolation(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getMessage()).isEqualTo("잘못된 입력값입니다.");
        assertThat(res.getBody().getCode()).isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("untranslatable character(22P05) → 400")
    void nul_22P05_400() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException("x",
                new SQLException("unicode escape value could not be translated", "22P05"));

        assertThat(handler.handleDataIntegrityViolation(ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("unique(23505) 는 기존대로 409 + code, 감싸진 22021 도 최종 안전망에서 400")
    void unique_still409_and_wrapped22021() {
        DataIntegrityViolationException unique = new DataIntegrityViolationException("x",
                new SQLException("dup", "23505"));
        assertThat(handler.handleDataIntegrityViolation(unique).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.handleDataIntegrityViolation(unique).getBody().getCode()).isEqualTo("DUPLICATE_VALUE");

        RuntimeException wrapped = new RuntimeException("commit failed", new SQLException("bad byte", "22021"));
        assertThat(handler.handleException(wrapped).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("TooManyRequestsException → 429 + Retry-After 헤더 + data.retryAfterSeconds")
    void tooManyRequests_retryAfter() {
        ResponseEntity<ApiResponse<Map<String, Long>>> res = handler.handleTooManyRequests(new TooManyRequestsException(42));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(res.getHeaders().getFirst("Retry-After")).isEqualTo("42");
        assertThat(res.getBody().getData()).containsEntry("retryAfterSeconds", 42L);
        assertThat(res.getBody().getCode()).isEqualTo("TOO_MANY_REQUESTS");
    }

    @Test
    @DisplayName("TooManyRequestsException 는 음수 대기 시간을 0으로 보정, 일반 CustomException(429)은 기존대로")
    void tooManyRequests_clamp() {
        assertThat(new TooManyRequestsException(-5).getRetryAfterSeconds()).isZero();
        ResponseEntity<ApiResponse<Void>> res = handler.handleCustomException(new CustomException(ErrorCode.TOO_MANY_REQUESTS));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(res.getHeaders().containsHeader("Retry-After")).isFalse();
    }

    @Test
    @DisplayName("ServiceUnavailableException → 503 + 안내 문구 + code")
    void serviceUnavailable_503() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleCustomException(new ServiceUnavailableException());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getBody().getMessage()).isEqualTo("일시적으로 서비스를 이용할 수 없습니다. 잠시 후 다시 시도해주세요.");
        assertThat(res.getBody().getCode()).isEqualTo("SERVICE_UNAVAILABLE");
    }
}

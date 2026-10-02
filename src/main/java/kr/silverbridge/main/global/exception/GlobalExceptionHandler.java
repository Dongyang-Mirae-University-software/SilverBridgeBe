package kr.silverbridge.main.global.exception;

import jakarta.validation.ConstraintViolationException;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // PostgreSQL SQLSTATE — 23505: unique_violation (그 외 23503 FK, 23502 NOT NULL, 23514 CHECK)
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    // 22021: character_not_in_repertoire(invalid byte sequence — 문자열의 NUL(\u0000) 등), 22P05: untranslatable_character.
    // 사용자 입력 탓이라 500이 아니라 400으로 안내한다.
    private static final Set<String> SQLSTATE_BAD_TEXT_INPUT = Set.of("22021", "22P05");

    // 커스텀 비즈니스 예외
    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiResponse<Void>> handleCustomException(CustomException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("CustomException: {}", errorCode.getMessage());
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.fail(errorCode));
    }

    // 429 + 남은 대기 시간 — Retry-After 헤더와 data.retryAfterSeconds 로 함께 내려준다
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiResponse<Map<String, Long>>> handleTooManyRequests(TooManyRequestsException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("TooManyRequestsException: {} (retryAfter={}s)", errorCode.getMessage(), e.getRetryAfterSeconds());
        return ResponseEntity
                .status(errorCode.getStatus())
                .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
                .body(ApiResponse.failWithData(errorCode, Map.of("retryAfterSeconds", e.getRetryAfterSeconds())));
    }

    // @Valid @RequestBody DTO 검증 실패 — 각 필드 오류를 줄바꿈으로 구분해 반환
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .distinct()
                .collect(Collectors.joining("\n"));
        if (message.isBlank()) {
            message = ErrorCode.INVALID_INPUT.getMessage();
        }
        log.warn("ValidationException: {}", message);
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.INVALID_INPUT, message));
    }

    // @RequestParam / @PathVariable 등 단일 값 검증 실패
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(v -> v.getMessage())
                .distinct()
                .collect(Collectors.joining("\n"));
        if (message.isBlank()) {
            message = ErrorCode.INVALID_INPUT.getMessage();
        }
        log.warn("ConstraintViolationException: {}", message);
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.INVALID_INPUT, message));
    }

    // JSON 파싱 실패 / 요청 바디 형식 오류
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("HttpMessageNotReadableException: {}", e.getMessage());
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.fail(ErrorCode.INVALID_INPUT, "요청 형식이 올바르지 않습니다. 입력값을 확인해주세요."));
    }

    // 필수 쿼리 파라미터 누락
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException e) {
        log.warn("MissingServletRequestParameterException: {}", e.getMessage());
        // 파일 파라미터(@RequestParam MultipartFile) 누락은 파라미터명 대신 "파일이 필요합니다"로 안내
        if ("MultipartFile".equals(e.getParameterType())) {
            return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.FILE_REQUIRED));
        }
        String message = String.format("'%s' 값이 필요합니다.", e.getParameterName());
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.INVALID_INPUT, message));
    }

    // multipart 파트 누락 (@RequestPart / @RequestParam MultipartFile 에 파일을 안 보낸 경우)
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException e) {
        log.warn("MissingServletRequestPartException: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.FILE_REQUIRED));
    }

    // multipart 요청이 아니거나 파싱 실패 (크기 초과는 아래 더 구체적인 핸들러가 먼저 처리)
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMultipart(MultipartException e) {
        log.warn("MultipartException: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.FILE_REQUIRED));
    }

    // 쿼리 파라미터 / 경로 변수 타입 불일치 (예: 숫자 자리에 문자)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("MethodArgumentTypeMismatchException: {}", e.getMessage());
        String message = String.format("'%s' 값의 형식이 올바르지 않습니다.", e.getName());
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.INVALID_INPUT, message));
    }

    // 비즈니스 로직의 잘못된 인자 (CustomException으로 감싸지지 않은 경우의 안전망)
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("IllegalArgumentException: {}", e.getMessage());
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.fail(ErrorCode.INVALID_INPUT));
    }

    // @PreAuthorize 권한 검증 실패
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        log.warn("AccessDeniedException: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail(ErrorCode.FORBIDDEN));
    }

    // DB 무결성 제약 위반 — 위반 종류(SQLState)에 따라 구분 처리한다.
    // unique 위반(23505)만 "중복"으로 안내하고, FK(23503)·NOT NULL(23502)·CHECK(23514) 등
    // 서버 측 정합성 결함은 "중복"으로 오안내하지 않는다(과거 카카오 가입 FK 위반이 "중복"으로 뭉개진 사례).
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        String sqlState = extractSqlState(e);

        // 23505 = unique_violation: 진짜 중복 (동시 요청으로 인한 이메일/전화번호 중복 등)
        if (SQLSTATE_UNIQUE_VIOLATION.equals(sqlState)) {
            // 메시지 본문에 중복 값(이메일/전화번호 등 PII)이 포함될 수 있어 SQLState만 남긴다.
            log.warn("DataIntegrityViolation(unique, sqlState={})", sqlState);
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ApiResponse.fail(ErrorCode.DUPLICATE_VALUE));
        }

        // 22021/22P05 = 문자열에 NUL 등 DB가 못 받는 문자 — 사용자 입력 문제라 400
        if (sqlState != null && SQLSTATE_BAD_TEXT_INPUT.contains(sqlState)) {
            log.warn("DataIntegrityViolation(bad text input, sqlState={})", sqlState);
            return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.INVALID_INPUT));
        }

        // 그 외(FK·NOT NULL·CHECK 등)는 서버 측 결함 — 진단을 위해 실제 원인을 ERROR로 남기고,
        // 사용자에겐 "중복"이 아닌 일반 서버 오류로 응답한다.
        log.error("DataIntegrityViolation(non-unique, sqlState={}): {}", sqlState, e.getMostSpecificCause().getMessage());
        return ResponseEntity
                .internalServerError()
                .body(ApiResponse.fail(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    // 예외 원인 체인을 따라 내려가 최초로 만나는 SQLException의 SQLState를 추출한다(없으면 null).
    private String extractSqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sqlEx) {
                return sqlEx.getSQLState();
            }
        }
        return null;
    }

    // 동시 상태 전이로 인한 낙관적 락(@Version) 충돌 — 다른 요청이 먼저 커밋됨
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        log.warn("OptimisticLockingFailure: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ErrorCode.CONCURRENT_MODIFICATION));
    }

    // 허용되지 않은 HTTP 메서드 (예: POST만 지원하는 API에 GET 요청)
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("HttpRequestMethodNotSupportedException: {}", e.getMessage());
        String allowed = e.getSupportedHttpMethods() == null
                ? ""
                : e.getSupportedHttpMethods().stream()
                        .map(m -> m.name())
                        .collect(Collectors.joining(", "));
        String message = allowed.isBlank()
                ? ErrorCode.METHOD_NOT_ALLOWED.getMessage()
                : String.format("%s (허용 방식: %s)", ErrorCode.METHOD_NOT_ALLOWED.getMessage(), allowed);
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.fail(ErrorCode.METHOD_NOT_ALLOWED, message));
    }

    // 지원하지 않는 Content-Type (예: application/xml로 요청)
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("HttpMediaTypeNotSupportedException: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.fail(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
    }

    // 존재하지 않는 API 경로
    // (활성화 조건: spring.mvc.throw-exception-if-no-handler-found=true, spring.web.resources.add-mappings=false)
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandlerFound(NoHandlerFoundException e) {
        log.warn("NoHandlerFoundException: {} {}", e.getHttpMethod(), e.getRequestURL());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ErrorCode.API_NOT_FOUND));
    }

    // Spring 6+ 정적 리소스 핸들러가 매핑 미스 요청을 받았을 때 던지는 예외
    // add-mappings=true(기본값) 환경에서 잘못된 경로 호출이 여기로 흘러옴 → 404로 정규화
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("NoResourceFoundException: {} {}", e.getHttpMethod(), e.getResourcePath());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ErrorCode.API_NOT_FOUND));
    }

    // 파일 업로드 크기 초과 (서블릿 레벨에서 잡힘 — 서비스 레벨 체크보다 먼저 발생)
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        log.warn("MaxUploadSizeExceededException: {}", e.getMessage());
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.fail(ErrorCode.FILE_TOO_LARGE));
    }

    // 예상치 못한 서버 오류 (최종 안전망)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        // 트랜잭션 커밋 시점 등에 감싸져 올라온 NUL 입력 오류도 400으로 (원인 체인의 SQLState 확인)
        String sqlState = extractSqlState(e);
        if (sqlState != null && SQLSTATE_BAD_TEXT_INPUT.contains(sqlState)) {
            log.warn("BadTextInput(sqlState={})", sqlState);
            return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.INVALID_INPUT));
        }
        log.error("UnhandledException: ", e);
        return ResponseEntity
                .internalServerError()
                .body(ApiResponse.fail(ErrorCode.INTERNAL_SERVER_ERROR));
    }
}

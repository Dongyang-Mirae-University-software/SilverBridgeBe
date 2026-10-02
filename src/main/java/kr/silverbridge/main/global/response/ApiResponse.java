package kr.silverbridge.main.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.Getter;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final boolean success;
    private final String message;
    private final T data;
    // 오류 식별 코드(ErrorCode enum 이름) — 실패 응답에만 실린다. 프론트가 문구 대신 코드로 분기할 수 있게 한다.
    private final String code;

    private ApiResponse(boolean success, String message, T data, String code) {
        this.success = success;
        this.message = message;
        this.data = data;
        this.code = code;
    }

    // 성공 (데이터 있음)
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, null, data, null);
    }

    // 성공 (데이터 없음)
    public static <T> ApiResponse<T> ok(String message) {
        return new ApiResponse<>(true, message, null, null);
    }

    // 실패
    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, message, null, null);
    }

    // 실패 (ErrorCode 기반 — 문구와 code 모두 ErrorCode에서)
    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return new ApiResponse<>(false, errorCode.getMessage(), null, errorCode.name());
    }

    // 실패 (문구만 상황에 맞게 바꾸고 code는 유지)
    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, message, null, errorCode.name());
    }

    // 실패 (부가 데이터 포함 — 예: 429의 retryAfterSeconds)
    public static <T> ApiResponse<T> failWithData(ErrorCode errorCode, T data) {
        return new ApiResponse<>(false, errorCode.getMessage(), data, errorCode.name());
    }
}

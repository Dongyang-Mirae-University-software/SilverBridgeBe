package kr.silverbridge.main.global.exception;

import lombok.Getter;

/**
 * 429 — 남은 대기 시간(초)을 함께 싣는 예외.
 * 핸들러가 {@code Retry-After} 헤더와 {@code data.retryAfterSeconds}로 내려 프론트가 "N초 후 다시 시도" 안내를 만들 수 있다.
 */
@Getter
public class TooManyRequestsException extends CustomException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(long retryAfterSeconds) {
        this(ErrorCode.TOO_MANY_REQUESTS, retryAfterSeconds);
    }

    public TooManyRequestsException(ErrorCode errorCode, long retryAfterSeconds) {
        super(errorCode);
        this.retryAfterSeconds = Math.max(retryAfterSeconds, 0);
    }
}

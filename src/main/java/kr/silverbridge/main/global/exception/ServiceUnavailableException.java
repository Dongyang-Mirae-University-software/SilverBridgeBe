package kr.silverbridge.main.global.exception;

/** 503 — 외부 의존(Redis 등) 장애로 일시적으로 처리할 수 없을 때. 500과 구분해 재시도 가능함을 알린다. */
public class ServiceUnavailableException extends CustomException {

    public ServiceUnavailableException() {
        super(ErrorCode.SERVICE_UNAVAILABLE);
    }
}

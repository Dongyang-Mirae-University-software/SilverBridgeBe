package kr.silverbridge.main.domain.camera.client;

/**
 * AI 서버에 닿지 못했다(연결 실패·시간 초과·5xx·키 미설정). 호출부가 "알 수 없음"(목록의 {@code status=null})
 * 또는 503으로 바꾼다 - "카메라가 꺼짐"과 섞지 않기 위해 별도 예외로 둔다.
 */
public class AiStreamUnavailableException extends RuntimeException {

    public AiStreamUnavailableException(String message) {
        super(message);
    }

    public AiStreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

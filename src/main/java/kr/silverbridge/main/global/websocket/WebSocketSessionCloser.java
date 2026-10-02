package kr.silverbridge.main.global.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * 토큰이 무효화된 사용자의 열린 WebSocket 세션을 서버가 닫는다(AUTH-G19·ADMIN-G28 세션 부분, 2026-09-10 M-5 한계 해소).
 *
 * <p>무효화 키는 핸드셰이크 때만 검사되므로, 정지·비밀번호 변경·역할 변경·탈퇴 시점에 이미 열려 있던 세션은
 * 이걸로 끊는다. 이벤트 발송 자체는 {@link WebSocketEventPublisher}의 수신자 상태 확인이 따로 막는다.</p>
 *
 * <p>닫기 코드는 {@code 1008 POLICY_VIOLATION}, reason은 고정 코드 {@value #CLOSE_REASON}뿐이다 -
 * 사유 상세(정지·역할 변경 등)나 개인정보를 싣지 않는다. 프론트는 이 코드를 받으면 <b>재연결하지 말고</b>
 * 로그아웃(또는 토큰 재발급 후 재연결)으로 처리해야 한다. 옛 토큰으로 재연결하면 핸드셰이크에서 거부된다.</p>
 *
 * <p>best-effort - 닫기 실패는 삼키고 WARN만 남긴다(호출자는 AFTER_COMMIT 리스너라 예외가 새면 안 된다).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketSessionCloser {

    /** 프론트 계약 값 - 바꾸면 FE의 재연결 억제 분기가 깨진다. */
    public static final String CLOSE_REASON = "AUTH_INVALIDATED";
    static final CloseStatus CLOSE_STATUS = CloseStatus.POLICY_VIOLATION.withReason(CLOSE_REASON);

    private final WebSocketSessionRegistry registry;

    /**
     * 그 사용자의 열린 세션을 모두 닫는다. 목록에서의 제거는 연결 종료 콜백(데코레이터)이 한다.
     *
     * @return 닫기를 시도한 세션 수
     */
    public int closeAll(String userId) {
        int attempted = 0;
        try {
            for (WebSocketSession session : registry.sessionsOf(userId)) {
                attempted++;
                try {
                    if (session.isOpen()) {
                        session.close(CLOSE_STATUS);
                    }
                } catch (Exception e) {
                    // 하나가 실패해도 나머지는 닫는다. 예외 원문에는 주소 등이 섞일 수 있어 클래스명만 남긴다
                    log.warn("[WS-SESSION-CLOSE-FAILED] userId={} error={}", userId, e.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException e) {
            log.warn("[WS-SESSION-CLOSE-FAILED] userId={} error={}", userId, e.getClass().getSimpleName());
        }
        if (attempted > 0) {
            log.info("[WS-SESSION-CLOSE] 토큰 무효화로 세션 종료 userId={} sessions={}", userId, attempted);
        }
        return attempted;
    }
}

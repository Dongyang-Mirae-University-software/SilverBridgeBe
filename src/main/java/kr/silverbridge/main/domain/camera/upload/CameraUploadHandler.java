package kr.silverbridge.main.domain.camera.upload;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * 브라우저 쪽 WebSocket 핸들러 - 메시지를 해석하지 않고 {@link CameraUploadRelay}로 넘긴다.
 *
 * <p>핸드셰이크에서 이미 티켓 인가와 AI 연결이 끝났다. 여기서는 수립 직후 연결을 목록에 올리고(같은 세션의 기존 연결은
 * 교체) 메시지를 중계할 뿐이다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CameraUploadHandler extends AbstractWebSocketHandler {

    private final CameraUploadConnections connections;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        CameraUploadRelay relay = relayOf(session);
        if (relay == null) {
            session.close(new CloseStatus(1011, "NO_RELAY"));
            return;
        }
        // 수립 전에 AI가 먼저 닫았다면(플래그 꺼짐·키 오류 등) 그 사유를 그대로 전한다
        if (!relay.attachBrowser(session)) {
            CloseStatus status = relay.finalStatus();
            session.close(status != null ? status : new CloseStatus(1011, "UPSTREAM_CLOSED"));
            return;
        }
        if (!connections.register(relay)) {
            relay.close(1013, "BUSY");
            return;
        }
        log.info("[CAMERA-UPLOAD] 수립: sessionId={}", relay.sessionId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        CameraUploadRelay relay = relayOf(session);
        if (relay != null) {
            relay.fromBrowserText(message.getPayload());
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        CameraUploadRelay relay = relayOf(session);
        if (relay != null) {
            relay.fromBrowserBinary(message.getPayload());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        CameraUploadRelay relay = relayOf(session);
        if (relay != null) {
            // 예외 원문은 남기지 않는다(클래스명만)
            log.info("[CAMERA-UPLOAD] 전송 오류: sessionId={}, error={}", relay.sessionId(), exception.getClass().getSimpleName());
            relay.close(1011, "TRANSPORT_ERROR");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        CameraUploadRelay relay = relayOf(session);
        if (relay != null) {
            relay.close(status.getCode(), "CLIENT_CLOSED");   // 이미 닫혔으면 무시된다. AI 연결을 반드시 정리한다
            connections.unregister(relay);
        }
    }

    private static CameraUploadRelay relayOf(WebSocketSession session) {
        Object value = session.getAttributes().get(CameraUploadHandshakeInterceptor.RELAY_ATTRIBUTE);
        return value instanceof CameraUploadRelay relay ? relay : null;
    }
}

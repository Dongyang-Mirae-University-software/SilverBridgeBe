package kr.silverbridge.main.domain.camera.upload;

import kr.silverbridge.main.domain.camera.config.CameraStreamProperties;
import kr.silverbridge.main.domain.camera.config.CameraUploadProperties;
import kr.silverbridge.main.domain.camera.service.CameraUploadService;
import kr.silverbridge.main.domain.camera.service.CameraUploadService.UploadGrant;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code /ws/camera-upload} 핸드셰이크 - 티켓 소비·인가 -> AI 내부 WS 연결 -> 수립.
 *
 * <p>모든 실패는 WS가 수립되기 전의 HTTP 오류다(401·403·404·409·503). AI 연결을 핸드셰이크 안에서 맺는 이유: AI가 죽었거나
 * 플래그가 꺼져 있으면 브라우저가 연결 성공을 보기 전에 503으로 알려 FE가 바로 JPEG로 폴백할 수 있고, 메시지를 AI 연결이
 * 맺어지기 전까지 쌓아 둘 필요가 없다. AI 키는 BE -> AI 헤더로만 쓴다(브라우저에 내리지 않는다).</p>
 *
 * <p>티켓은 쿼리로 오지만 60초·1회·소비 시점에 폐기되고 sessionId는 티켓에서만 얻는다(URL·메시지로 받지 않는다).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CameraUploadHandshakeInterceptor implements HandshakeInterceptor {

    static final String RELAY_ATTRIBUTE = "cameraUploadRelay";
    private static final String AI_INGEST_PATH = "/api/v1/stream-sessions/{sessionId}/ingest-ws";

    private final CameraUploadService uploadService;
    private final CameraUploadConnections connections;
    private final CameraUploadProperties uploadProperties;
    private final CameraStreamProperties streamProperties;

    @Value("${camera.recommended-fps:10}")
    private int recommendedFps;

    // 핸드셰이크는 한 요청 스레드 안에서 beforeHandshake -> afterHandshake로 이어진다. 업그레이드 실패 때 AI 연결을 정리하려고 건다.
    private final ThreadLocal<CameraUploadRelay> pending = new ThreadLocal<>();

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        UploadGrant grant;
        try {
            grant = uploadService.authorize(extractTicket(request));
        } catch (CustomException e) {
            reject(response, e.getErrorCode());
            return false;
        } catch (RuntimeException e) {
            log.warn("[CAMERA-UPLOAD] 핸드셰이크 인가 오류 - 503: error={}", e.getClass().getSimpleName());
            reject(response, ErrorCode.SERVICE_UNAVAILABLE);
            return false;
        }

        if (!connections.hasCapacityFor(grant.sessionId())) {
            log.warn("[CAMERA-UPLOAD] 동시 연결 상한 - 거절: sessionId={}", grant.sessionId());
            reject(response, ErrorCode.CAMERA_UPLOAD_UNAVAILABLE);
            return false;
        }

        CameraUploadRelay relay = new CameraUploadRelay(grant, uploadProperties, recommendedFps);
        try {
            relay.attachAi(connectToAi(relay));
        } catch (CustomException e) {
            reject(response, e.getErrorCode());
            return false;
        }
        attributes.put(RELAY_ATTRIBUTE, relay);
        pending.set(relay);
        // 토큰 무효화(정지·비밀번호 변경·역할 변경·탈퇴) 때 서버가 이 세션도 닫을 수 있게 사용자 ID를 건다
        attributes.put("userId", grant.wardId());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 업그레이드가 실패하면 afterConnectionEstablished가 불리지 않는다 - AI 연결이 남지 않게 정리한다
        CameraUploadRelay relay = pending.get();
        pending.remove();
        if (exception != null && relay != null) {
            relay.close(1011, "HANDSHAKE_FAILED");
        }
    }

    private WebSocketSession connectToAi(CameraUploadRelay relay) {
        String apiKey = streamProperties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new CustomException(ErrorCode.CAMERA_UPLOAD_UNAVAILABLE);
        }
        URI uri = UriComponentsBuilder.fromUriString(aiWsBase())
                .path(AI_INGEST_PATH)
                .buildAndExpand(relay.sessionId())
                .encode()
                .toUri();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("X-API-Key", apiKey);
        try {
            return new StandardWebSocketClient()
                    .execute(new AiSideHandler(relay), headers, uri)
                    .get(uploadProperties.getAiConnectTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("[CAMERA-UPLOAD] AI 연결 시간 초과 - 503: sessionId={}", relay.sessionId());
            throw new CustomException(ErrorCode.CAMERA_UPLOAD_UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustomException(ErrorCode.CAMERA_UPLOAD_UNAVAILABLE);
        } catch (Exception e) {
            // 예외 원문(URL·헤더 정보 포함 가능)은 남기지 않는다
            log.warn("[CAMERA-UPLOAD] AI 연결 실패 - 503: sessionId={}, error={}",
                    relay.sessionId(), e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.CAMERA_UPLOAD_UNAVAILABLE);
        }
    }

    // AI WebSocket 기준 주소: 별도 설정이 있으면 그것, 없으면 시청 중계와 같은 AI HTTP 주소의 스킴만 ws(s)로 바꾼다
    private String aiWsBase() {
        String configured = uploadProperties.getAiWsBaseUrl();
        if (configured != null && !configured.isBlank()) {
            return stripTrailingSlash(configured.trim());
        }
        String http = stripTrailingSlash(streamProperties.getAiBaseUrl().trim());
        if (http.startsWith("https://")) {
            return "wss://" + http.substring("https://".length());
        }
        if (http.startsWith("http://")) {
            return "ws://" + http.substring("http://".length());
        }
        return http;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String extractTicket(ServerHttpRequest request) {
        String query = request.getURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String param : query.split("&")) {
            if (param.startsWith("ticket=")) {
                return param.substring("ticket=".length());
            }
        }
        return null;
    }

    private static void reject(ServerHttpResponse response, ErrorCode code) {
        HttpStatus status = code.getStatus();
        response.setStatusCode(status);
    }

    /** AI 쪽 연결의 수신 처리 - keyframe_request만 브라우저로 전하고, AI가 닫으면 그 코드를 브라우저에 전한다. */
    private static final class AiSideHandler extends TextWebSocketHandler {

        private final CameraUploadRelay relay;

        AiSideHandler(CameraUploadRelay relay) {
            this.relay = relay;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            relay.fromAiText(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            relay.onAiClosed(status);
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            relay.close(1011, "UPSTREAM_ERROR");
        }
    }
}

package kr.silverbridge.main.domain.anomaly.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.service.AnomalyDetectionService;
import kr.silverbridge.main.domain.camera.service.CameraService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 서버 이상감지 WebSocket({@code /api/v1/ws/live}) 구독자.
 *
 * <p>AI 서버는 알림을 밀어주지 않고(웹훅 없음) 자체 WS로 분석 결과를 broadcast만 한다. 따라서 백엔드가
 * <b>클라이언트로 붙어</b> 신호를 받는다 — AI 서버는 무변경(카메라 도메인의 "AI = 무변경 내부 서비스" 원칙).</p>
 *
 * <p>동작(AI 문서 §6-2):</p>
 * <ol>
 *   <li>연결(헤더 {@code x-api-key} 인증 — 쿼리 방식은 접속 URL 로깅에 키가 남아 사용하지 않는다)</li>
 *   <li>연결 직후 {@code {"action":"list"}} → 응답 {@code live_streams} 중 <b>우리 {@code cameras}에 등록된
 *       세션만</b> {@code subscribe}. 미등록 세션은 구독조차 하지 않아 "알 수 없는 세션" 로그 폭주를 구조적으로 막는다.</li>
 *   <li>{@code latest_analysis} 수신 → {@link AnomalyDetectionService}가 판정·이력화</li>
 *   <li>{@code live_streams}는 세션 생성/종료 시 전체 연결에 broadcast되므로, 새로 켜진 카메라도 자동 구독된다</li>
 * </ol>
 *
 * <p><b>기동을 막지 않는다</b>: 연결은 {@link ApplicationReadyEvent} 이후 시도하며, 실패해도 예외를 던지지 않고
 * 지수 백오프로 재시도한다(AI 서버가 죽어 있어도 백엔드는 정상 기동·서비스). API Key가 없거나
 * {@code anomaly.enabled=false}면 구독만 비활성화한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiLiveStreamSubscriber extends TextWebSocketHandler {

    private final AnomalyProperties properties;
    private final AnomalySignalParser signalParser;
    private final AnomalyDetectionService detectionService;
    private final CameraService cameraService;
    private final ObjectMapper objectMapper;
    private final TaskScheduler taskScheduler;

    /** 현재 구독 중인 세션 — 재연결 시 초기화된다(서버 측 구독 상태가 날아가므로). */
    private final Set<String> subscribedSessions = ConcurrentHashMap.newKeySet();

    private volatile WebSocketSession session;
    private volatile int reconnectAttempts;
    private volatile boolean shuttingDown;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.isEnabled()) {
            log.info("[ANOMALY] 이상감지 수신 비활성(anomaly.enabled=false) — AI WS 미접속");
            return;
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            log.warn("[ANOMALY] AI_API_KEY 미설정 — 이상감지 수신 비활성(앱은 정상 기동). "
                    + "이상감지를 쓰려면 .env.dev에 AI_API_KEY를 주입할 것");
            return;
        }
        connect();
    }

    @PreDestroy
    public void stop() {
        shuttingDown = true;
        closeQuietly();
    }

    private void connect() {
        if (shuttingDown) {
            return;
        }

        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("x-api-key", properties.getApiKey());

        log.info("[ANOMALY] AI WS 접속 시도: url={}", properties.getWsUrl());
        new StandardWebSocketClient()
                .execute(this, headers, URI.create(properties.getWsUrl()))
                .whenComplete((connected, error) -> {
                    if (error != null) {
                        log.warn("[ANOMALY] AI WS 접속 실패: {}", error.getMessage());
                        scheduleReconnect();
                    }
                });
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        this.session = session;
        this.reconnectAttempts = 0;
        subscribedSessions.clear();   // 재연결이면 서버 측 구독이 사라졌으므로 다시 구독해야 한다
        log.info("[ANOMALY] AI WS 연결됨 — 세션 목록 요청");
        send("{\"action\":\"list\"}");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            switch (root.path("type").asText("")) {
                case "live_streams" -> subscribeRegisteredSessions(root.path("data"));
                case "latest_analysis" -> signalParser.parse(root).ifPresent(detectionService::handle);
                default -> { /* connected·pong·subscribed·session_status 등은 처리 대상 아님 */ }
            }
        } catch (Exception e) {
            // 메시지 1건의 실패가 커넥션을 끊지 않도록 격리 — 다음 프레임은 계속 처리한다
            log.error("[ANOMALY] AI WS 메시지 처리 실패 — 해당 메시지만 폐기", e);
        }
    }

    /** AI가 알려준 세션 목록 중 백엔드에 등록된(=소유자를 아는) 카메라만 구독한다. */
    private void subscribeRegisteredSessions(JsonNode streams) {
        if (!streams.isArray()) {
            return;
        }
        for (JsonNode stream : streams) {
            String sessionId = stream.path("sessionId").asText(null);
            if (sessionId == null || sessionId.isBlank() || subscribedSessions.contains(sessionId)) {
                continue;
            }
            if (cameraService.findOwnerBySessionId(sessionId).isEmpty()) {
                log.debug("[ANOMALY] 미등록 세션 — 구독하지 않음: sessionId={}", sessionId);
                continue;
            }
            if (send("{\"action\":\"subscribe\",\"sessionId\":\"" + sessionId + "\"}")) {
                subscribedSessions.add(sessionId);
                log.info("[ANOMALY] 세션 구독: sessionId={}", sessionId);
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[ANOMALY] AI WS 전송 오류: {}", exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        this.session = null;
        subscribedSessions.clear();
        log.warn("[ANOMALY] AI WS 연결 종료: code={}, reason={}", status.getCode(), status.getReason());
        scheduleReconnect();
    }

    /** 지수 백오프 재접속 — AI 서버 재시작·네트워크 순단에도 스스로 복구한다. */
    private void scheduleReconnect() {
        if (shuttingDown) {
            return;
        }
        long min = properties.getReconnectMinSeconds();
        long max = properties.getReconnectMaxSeconds();
        long delay = Math.min(max, min * (1L << Math.min(reconnectAttempts, 5)));   // 2,4,8,16,32,60…(상한)
        reconnectAttempts++;

        log.info("[ANOMALY] AI WS 재접속 예약: {}초 후 (시도 {}회차)", delay, reconnectAttempts);
        taskScheduler.schedule(this::connect, Instant.now().plus(Duration.ofSeconds(delay)));
    }

    private boolean send(String payload) {
        WebSocketSession current = this.session;
        if (current == null || !current.isOpen()) {
            return false;
        }
        try {
            current.sendMessage(new TextMessage(payload));
            return true;
        } catch (IOException e) {
            log.warn("[ANOMALY] AI WS 전송 실패: payload={}, error={}", payload, e.getMessage());
            return false;
        }
    }

    private void closeQuietly() {
        WebSocketSession current = this.session;
        if (current == null || !current.isOpen()) {
            return;
        }
        try {
            current.close(CloseStatus.NORMAL);
        } catch (IOException e) {
            log.debug("[ANOMALY] AI WS 종료 중 오류(무시): {}", e.getMessage());
        }
    }
}

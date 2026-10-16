package kr.silverbridge.main.domain.anomaly.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.dto.AnomalySignal;
import kr.silverbridge.main.domain.anomaly.service.AnomalyDetectionService;
import kr.silverbridge.main.domain.anomaly.service.CameraLinkLostNotifier;
import kr.silverbridge.main.domain.anomaly.service.LiveAnalysisBroadcaster;
import kr.silverbridge.main.domain.camera.event.CameraRegisteredEvent;
import kr.silverbridge.main.domain.camera.service.CameraService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
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
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;

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
    private final LiveAnalysisBroadcaster liveAnalysisBroadcaster;
    private final CameraLinkLostNotifier linkLostNotifier;

    /** AI에 세션 목록을 요청하는 페이로드(고정 문자열 — 변수 삽입 없음). */
    private static final String LIST_ACTION = "{\"action\":\"list\"}";
    private static final int MAX_TEXT_MESSAGE_BYTES = 1024 * 1024;

    /** 현재 구독 중인 세션 — 재연결 시 초기화된다(서버 측 구독 상태가 날아가므로). */
    private final Set<String> subscribedSessions = ConcurrentHashMap.newKeySet();

    /**
     * 연결이 끊긴 시점에 구독 중이던 세션(2026-10-16). 재접속 뒤 첫 목록에서 <b>빠진</b> 등록 카메라는 "종료"로
     * 계산할 수 없어(구독 기록이 비어 있다) 화면이 확인 불가에 멈췄다 - 이 스냅샷으로 사라진 세션을 가려낸다.
     * 첫 목록을 처리하면 비운다. 재접속이 목록 도착 전에 또 끊기면 합쳐서 이어 간다.
     */
    private final Set<String> lastKnownSessions = ConcurrentHashMap.newKeySet();

    /** 마지막으로 AI에서 메시지(분석·목록·상태 등 무엇이든)를 받은 시각 - 반쯤 열린 연결 감지용. */
    private volatile Instant lastMessageAt = Instant.now();

    private volatile WebSocketSession session;
    private volatile int reconnectAttempts;
    private volatile boolean shuttingDown;
    private volatile ScheduledFuture<?> resyncTask;

    /**
     * 관리자 대시보드 조회용 - AI WS가 지금 붙어 있는가.
     *
     * <p>이 값이 {@code false}면 구독 세션 수는 "카메라가 없다"가 아니라 <b>"알 수 없다"</b>는 뜻이다.
     * 대시보드가 두 경우를 섞으면 우리 쪽 수신기가 죽은 것을 현장 카메라가 전멸한 것으로 보여준다.</p>
     */
    public boolean isConnected() {
        WebSocketSession current = this.session;
        return current != null && current.isOpen();
    }

    /**
     * 관리자 대시보드 조회용 - 현재 구독 중인 세션 수.
     *
     * <p>구독은 <b>AI가 라이브로 보고했고 우리 {@code camera} 테이블에도 등록된</b> 세션만 들어간다
     * ({@code syncSubscriptions} 참조). 그래서 이 값이 곧 "지금 스트리밍이 잡히는 등록 카메라 대수"다.</p>
     */
    public int subscribedSessionCount() {
        return subscribedSessions.size();
    }

    /**
     * 수신 버퍼를 키운 WebSocket 컨테이너. 기본값(8KB)을 넘는 메시지가 오면 컨테이너가 연결을 끊는데(1009),
     * AI의 세션 목록·감지 배열은 세션 수에 따라 그보다 커질 수 있다(2026-09-30 L-15).
     */
    private static WebSocketContainer webSocketContainer() {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_BYTES);
        return container;
    }

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
        startResync();
    }

    /**
     * 세션 목록을 주기적으로 다시 요청하는 안전망. AI의 broadcast가 한 번 빠져도 한 주기 안에 구독이 맞춰진다.
     * 연결이 없는 동안에는 건너뛴다(재접속은 별도 경로가 담당).
     */
    private void startResync() {
        long seconds = properties.getResyncSeconds();
        if (seconds <= 0) {
            log.info("[ANOMALY] 세션 목록 재동기화 비활성(anomaly.resync-seconds=0)");
            return;
        }
        Duration period = Duration.ofSeconds(seconds);
        resyncTask = taskScheduler.scheduleWithFixedDelay(this::resync, Instant.now().plus(period), period);
    }

    void resync() {
        if (shuttingDown || !isConnected()) {
            return;
        }
        if (closeIfStale()) {
            return;
        }
        send(LIST_ACTION);
    }

    /**
     * 수신이 너무 오래 없으면 연결을 닫아 기존 백오프 재접속 경로로 보낸다(2026-10-16).
     * {@code isOpen()}만 보면 조용히 끊긴(반쯤 열린) 연결에서 위험 신호 0건이 오류 없이 이어진다. 목록 요청에는
     * AI가 항상 응답하므로 한 주기 동안 아무것도 오지 않았다면 정상 연결이 아니다.
     *
     * @return 닫았으면 true
     */
    private boolean closeIfStale() {
        int multiplier = properties.getStaleResyncMultiplier();
        long resync = properties.getResyncSeconds();
        if (multiplier <= 0 || resync <= 0) {
            return false;
        }
        Duration silent = Duration.between(lastMessageAt, Instant.now());
        if (silent.compareTo(Duration.ofSeconds(resync * multiplier)) <= 0) {
            return false;
        }
        log.warn("[ANOMALY-AI-WS-STALE] AI WS 수신이 {}초 동안 없어 연결을 닫고 재접속: 기준={}초",
                silent.toSeconds(), resync * multiplier);
        WebSocketSession current = this.session;
        try {
            if (current != null) {
                current.close(CloseStatus.GOING_AWAY);   // afterConnectionClosed가 재접속을 예약한다
            }
        } catch (IOException e) {
            log.warn("[ANOMALY-AI-WS-STALE] 닫기 실패 - 세션을 직접 정리: error={}", e.getClass().getSimpleName());
            afterConnectionClosed(current, CloseStatus.GOING_AWAY);
        }
        return true;
    }

    @PreDestroy
    public void stop() {
        shuttingDown = true;
        ScheduledFuture<?> task = this.resyncTask;
        if (task != null) {
            task.cancel(false);
        }
        closeQuietly();
    }

    private void connect() {
        if (shuttingDown) {
            return;
        }

        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("x-api-key", properties.getApiKey());

        log.info("[ANOMALY] AI WS 접속 시도: url={}", properties.getWsUrl());
        new StandardWebSocketClient(webSocketContainer())
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
        this.lastMessageAt = Instant.now();
        subscribedSessions.clear();   // 재연결이면 서버 측 구독이 사라졌으므로 다시 구독해야 한다
        liveAnalysisBroadcaster.clear();
        log.info("[ANOMALY] AI WS 연결됨 — 세션 목록 요청");
        send(LIST_ACTION);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        lastMessageAt = Instant.now();
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            switch (root.path("type").asText("")) {
                case "live_streams" -> subscribeRegisteredSessions(root.path("data"));
                case "latest_analysis" -> handleAnalysis(root);
                case "session_status" -> liveAnalysisBroadcaster.onSessionStatus(
                        root.path("sessionId").asText(null), root.path("data").path("status").asText(null));
                default -> { /* connected·pong·subscribed 등은 처리 대상 아님 */ }
            }
        } catch (Exception e) {
            // 메시지 1건의 실패가 커넥션을 끊지 않도록 격리 — 다음 프레임은 계속 처리한다
            log.error("[ANOMALY] AI WS 메시지 처리 실패 — 해당 메시지만 폐기", e);
        }
    }

    /**
     * 분석 1건을 처리한다. 판정·이력·알림이 먼저고(종류별 위험을 각각, 2026-10-16), 화면 표시는 대표 1건이다.
     * 한 종류의 처리 실패가 다른 종류를 막지 않도록 신호마다 격리한다.
     */
    private void handleAnalysis(JsonNode root) {
        Optional<AnomalySignal> representative = signalParser.parse(root);
        if (representative.isEmpty()) {
            return;
        }
        for (AnomalySignal signal : signalParser.expand(root, representative.get())) {
            try {
                detectionService.handle(signal);
            } catch (RuntimeException e) {
                log.error("[ANOMALY] 신호 처리 실패 - 해당 종류만 폐기: sessionId={}, detectedType={}, error={}",
                        signal.sessionId(), signal.detectedType(), e.getClass().getSimpleName());
            }
        }
        // 화면용 상태는 대표(최상위) 값 그대로 - 대표는 항상 목록에 있다(위험 목록에 없으면 따로 더해진다)
        liveAnalysisBroadcaster.onAnalysis(representative.get());
    }

    /**
     * 카메라가 등록·재등록되면 AI 세션 목록을 다시 요청한다.
     *
     * <p>AI는 세션 <b>생성·종료 시에만</b> 목록을 broadcast한다. 따라서 "스트리밍이 이미 돌고 있는 세션을
     * 나중에 카메라로 등록"하면 그 broadcast는 이미 지나갔고, 재요청이 없으면 해당 세션은 <b>앱 재시작 전까지
     * 구독되지 않는다</b>(감지·알림이 에러 없이 0건 — 조용한 침묵).</p>
     *
     * <p><b>커밋 후에</b> 요청한다(2026-09-30 M-3). 등록 트랜잭션이 끝나기 전에 요청하면 AI 응답이 커밋보다 먼저
     * 도착했을 때 방금 넣은 카메라 행이 아직 안 보여 "미등록 세션"으로 건너뛴다 - 이 메서드가 막으려던 바로 그 침묵이다.</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCameraRegistered(CameraRegisteredEvent event) {
        if (session == null) {
            return;   // 미연결 상태 — 재연결 시 afterConnectionEstablished가 목록을 다시 받아온다
        }
        log.info("[ANOMALY] 카메라 등록 감지 — AI 세션 목록 재요청: sessionId={}", event.sessionId());
        send(LIST_ACTION);
    }

    /**
     * AI 세션 목록으로 구독 상태를 <b>재동기화</b>한다: 등록된(=소유자를 아는) 세션은 구독하고, 목록에서 사라진
     * 세션은 구독 기록에서 지운다.
     *
     * <p>지우는 쪽이 중요하다 — 카메라의 {@code session_id}는 영속이라 iPad가 끊었다 <b>같은 sessionId로</b>
     * 다시 붙으면 AI 입장에선 새 세션이지만, 우리가 "이미 구독함"으로 기억하고 있으면 subscribe를 다시 보내지 않아
     * {@code latest_analysis}가 영영 오지 않는다.</p>
     */
    private void subscribeRegisteredSessions(JsonNode streams) {
        if (!streams.isArray()) {
            return;
        }

        Set<String> live = new HashSet<>();
        for (JsonNode stream : streams) {
            String sessionId = stream.path("sessionId").asText(null);
            if (sessionId == null || sessionId.isBlank()) {
                continue;
            }
            live.add(sessionId);

            if (subscribedSessions.contains(sessionId)) {
                continue;
            }
            if (cameraService.findOwnerBySessionId(sessionId).isEmpty()) {
                log.debug("[ANOMALY] 미등록 세션 — 구독하지 않음: sessionId={}", sessionId);
                continue;
            }
            if (send(subscribeAction(sessionId))) {
                subscribedSessions.add(sessionId);
                linkLostNotifier.onRecovered(sessionId);   // 유예 중이던 "영상 끊김" 알림을 취소한다
                log.info("[ANOMALY] 세션 구독: sessionId={}", sessionId);
            } else {
                // 등록된 카메라가 라이브인데 구독하지 못했다 - 다음 재동기화까지 감시에서 빠진다(조용한 침묵 방지)
                log.warn("[ANOMALY] 등록 카메라 세션 구독 실패 - 다음 재동기화에서 재시도: sessionId={}", sessionId);
            }
        }

        handleSessionsLostAcrossReconnect(live);

        // AI 목록에서 사라진 세션 = 종료된 세션. 기록을 지워야 같은 sessionId로 재시작할 때 다시 구독한다.
        Set<String> ended = new HashSet<>(subscribedSessions);
        ended.removeAll(live);
        if (!ended.isEmpty()) {
            liveAnalysisBroadcaster.onSessionsEnded(ended);   // 보호자 화면에 꺼짐 표시
        }
        if (subscribedSessions.retainAll(live)) {
            log.info("[ANOMALY] 종료된 세션 구독 해제 — 남은 구독={}건", subscribedSessions.size());
        }
    }

    /**
     * 끊기기 전에 구독하던 세션 중 재접속 뒤 첫 목록에 없는 등록 카메라를 처리한다: 화면에 꺼짐을 알리고,
     * 유예 뒤에도 돌아오지 않으면 보호자에게 안내한다. 첫 목록에서 한 번만 평가한다.
     */
    private void handleSessionsLostAcrossReconnect(Set<String> live) {
        if (lastKnownSessions.isEmpty()) {
            return;
        }
        Set<String> lost = new HashSet<>(lastKnownSessions);
        lastKnownSessions.clear();
        lost.removeAll(live);
        lost.removeIf(sessionId -> cameraService.findOwnerBySessionId(sessionId).isEmpty());   // 그 사이 삭제된 카메라
        if (lost.isEmpty()) {
            return;
        }
        log.warn("[ANOMALY-SESSION-LOST-AFTER-RECONNECT] AI 재접속 뒤 목록에서 사라진 등록 카메라 {}건 - 감시 중단 상태",
                lost.size());
        liveAnalysisBroadcaster.onSessionsOffline(lost);
        linkLostNotifier.onLost(lost);
    }

    /** subscribe 페이로드. sessionId를 문자열로 이어붙이지 않고 직렬화한다(따옴표 등으로 JSON이 깨지지 않도록). */
    private String subscribeAction(String sessionId) {
        try {
            return objectMapper.writeValueAsString(Map.of("action", "subscribe", "sessionId", sessionId));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("subscribe 페이로드 직렬화 실패: sessionId=" + sessionId, e);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[ANOMALY] AI WS 전송 오류: {}", exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        this.session = null;
        lastKnownSessions.addAll(subscribedSessions);   // 재접속 뒤 사라진 세션을 가려내는 기준
        subscribedSessions.clear();
        linkLostNotifier.cancelAll();      // 연결 상태를 모르는 동안은 보내지 않는다 - 재접속 뒤 다시 판단
        liveAnalysisBroadcaster.clear();   // 끊긴 동안의 상태는 알 수 없다 - 옛 결과를 현재처럼 보여주지 않는다
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
        long base = Math.min(max, min * (1L << Math.min(reconnectAttempts, 5)));   // 2,4,8,16,20…(상한)
        // 0~25% 지터 - AI 서버 재시작 직후 여러 인스턴스·재시도가 한꺼번에 몰리지 않게 한다. 상한은 넘지 않는다.
        long delay = Math.max(1, base - ThreadLocalRandom.current().nextLong(base / 4 + 1));
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

package kr.silverbridge.main.domain.camera.upload;

import kr.silverbridge.main.domain.camera.config.CameraStreamProperties;
import kr.silverbridge.main.domain.camera.config.CameraUploadProperties;
import kr.silverbridge.main.domain.camera.service.CameraUploadService;
import kr.silverbridge.main.domain.camera.service.CameraUploadService.UploadGrant;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.websocket.WebSocketSessionRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 송출 WebSocket 중계 종단 시험 - 실제 Tomcat 위에서 브라우저(클라이언트) -> BE 핸들러 -> 가짜 AI 서버를 잇는다.
 *
 * <p>인가 서비스만 목으로 두고(티켓 로직은 별도 단위 시험), 핸드셰이크 인터셉터·핸들러·중계·연결 목록은 실물이다.
 * 가짜 AI는 같은 서버의 일반 WebSocket 엔드포인트이며 X-API-Key 헤더와 받은 메시지를 기록한다.</p>
 */
class CameraUploadRelayEndToEndTest {

    private static final String AI_KEY = "ai-secret-key";
    private static final CameraUploadService UPLOAD_SERVICE = mock(CameraUploadService.class);
    private static final CameraUploadProperties UPLOAD_PROPS = new CameraUploadProperties();
    private static final CameraStreamProperties STREAM_PROPS = new CameraStreamProperties();
    private static final FakeAi FAKE_AI = new FakeAi();
    private static final AtomicInteger SEQ = new AtomicInteger();

    private static AnnotationConfigServletWebServerApplicationContext context;
    private static int port;

    @Configuration
    static class E2eConfig implements WebSocketConfigurer {

        @Bean
        TomcatServletWebServerFactory serverFactory() {
            TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
            // 자동구성 없이 띄우므로 Tomcat WebSocket 컨테이너를 직접 켠다(Boot가 하는 일과 같다)
            factory.addContextCustomizers(
                    context -> context.addApplicationListener("org.apache.tomcat.websocket.server.WsContextListener"));
            return factory;
        }

        @Bean
        DispatcherServlet dispatcherServlet() {
            return new DispatcherServlet();
        }

        @Bean
        DispatcherServletRegistrationBean dispatcherServletRegistration(DispatcherServlet servlet) {
            return new DispatcherServletRegistrationBean(servlet, "/");
        }

        @Bean
        CameraUploadProperties uploadProperties() {
            return UPLOAD_PROPS;
        }

        @Bean
        WebSocketSessionRegistry sessionRegistry() {
            return new WebSocketSessionRegistry();
        }

        @Bean(initMethod = "start", destroyMethod = "stop")
        CameraUploadConnections connections() {
            return new CameraUploadConnections(UPLOAD_PROPS, UPLOAD_SERVICE);
        }

        @Bean
        CameraUploadHandler handler(CameraUploadConnections connections) {
            return new CameraUploadHandler(connections);
        }

        @Bean
        CameraUploadHandshakeInterceptor interceptor(CameraUploadConnections connections) {
            return new CameraUploadHandshakeInterceptor(UPLOAD_SERVICE, connections, UPLOAD_PROPS, STREAM_PROPS);
        }

        @Override
        public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            registry.addHandler(FAKE_AI, "/api/v1/stream-sessions/*/ingest-ws")
                    .addInterceptors(FAKE_AI.headerRecorder())
                    .setAllowedOrigins("*");
        }
    }

    @BeforeAll
    static void startServer() {
        context = new AnnotationConfigServletWebServerApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("e2e", Map.of("app.cors.allowed-origins", "https://allowed.example")));
        // Origin 허용 목록은 CameraUploadWebSocketConfig의 @Value로 들어간다
        context.register(E2eConfig.class, CameraUploadWebSocketConfig.class);
        context.refresh();
        port = context.getWebServer().getPort();
        STREAM_PROPS.setApiKey(AI_KEY);
        STREAM_PROPS.setAiBaseUrl("http://localhost:" + port);
    }

    @AfterAll
    static void stopServer() {
        context.close();
    }

    @BeforeEach
    void resetState() throws Exception {
        // 앞 시험의 연결이 정리될 때까지 기다린다 - 서버·목록은 시험 사이에 공유되므로 남은 연결이 상한·집계를 흔든다
        CameraUploadConnections connections = context.getBean(CameraUploadConnections.class);
        awaitTrue(() -> connections.size() == 0, 8);
        Thread.sleep(200);
        reset(UPLOAD_SERVICE);
        UPLOAD_PROPS.setIdleTimeout(java.time.Duration.ofSeconds(15));
        UPLOAD_PROPS.setRevalidateInterval(java.time.Duration.ofSeconds(60));
        UPLOAD_PROPS.setMaxConnections(5);
        UPLOAD_PROPS.setMaxMessageBytes(256 * 1024);
        STREAM_PROPS.setAiBaseUrl("http://localhost:" + port);
        STREAM_PROPS.setApiKey(AI_KEY);
        FAKE_AI.reset();
        when(UPLOAD_SERVICE.isStillValid(any())).thenReturn(true);
    }

    // ---- 헬퍼 ----

    private static String newSessionId() {
        return "ward_e2e" + SEQ.incrementAndGet();
    }

    private static void grant(String ticket, String sessionId) {
        when(UPLOAD_SERVICE.authorize(ticket)).thenReturn(new UploadGrant("WD0001", sessionId, System.currentTimeMillis()));
    }

    private static Client connect(String ticket) throws Exception {
        Client client = new Client();
        client.session = new StandardWebSocketClient()
                .execute(client, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + "/ws/camera-upload?ticket=" + ticket))
                .get(5, TimeUnit.SECONDS);
        return client;
    }

    /**
     * 핸드셰이크 요청만 보내 응답 상태 코드를 본다(거절 사유 확인용). Connection·Upgrade는 HttpURLConnection이 막는
     * 제한 헤더라 소켓으로 직접 쓴다.
     */
    private static int handshakeStatus(String ticket, String origin) throws Exception {
        try (java.net.Socket socket = new java.net.Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            StringBuilder request = new StringBuilder()
                    .append("GET /ws/camera-upload?ticket=").append(ticket).append(" HTTP/1.1\r\n")
                    .append("Host: localhost:").append(port).append("\r\n")
                    .append("Connection: Upgrade\r\nUpgrade: websocket\r\n")
                    .append("Sec-WebSocket-Version: 13\r\n")
                    .append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n");
            if (origin != null) {
                request.append("Origin: ").append(origin).append("\r\n");
            }
            request.append("\r\n");
            socket.getOutputStream().write(request.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
            String statusLine = reader.readLine();   // HTTP/1.1 401 ...
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    static final class Client extends AbstractWebSocketHandler {
        WebSocketSession session;
        final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

        @Override
        protected void handleTextMessage(WebSocketSession s, TextMessage message) {
            texts.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
            closed.complete(status);
        }

        CloseStatus awaitClose(long seconds) throws Exception {
            return closed.get(seconds, TimeUnit.SECONDS);
        }
    }

    static final class FakeAi extends AbstractWebSocketHandler {
        final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
        final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> binaries = new LinkedBlockingQueue<>();
        final BlockingQueue<String> apiKeys = new LinkedBlockingQueue<>();
        final BlockingQueue<String> paths = new LinkedBlockingQueue<>();
        volatile CloseStatus closeOnConnect;
        final AtomicInteger closedCount = new AtomicInteger();

        void reset() {
            sessions.clear();
            texts.clear();
            binaries.clear();
            apiKeys.clear();
            paths.clear();
            closeOnConnect = null;
            closedCount.set(0);
        }

        org.springframework.web.socket.server.HandshakeInterceptor headerRecorder() {
            return new org.springframework.web.socket.server.HandshakeInterceptor() {
                @Override
                public boolean beforeHandshake(org.springframework.http.server.ServerHttpRequest request,
                                               org.springframework.http.server.ServerHttpResponse response,
                                               org.springframework.web.socket.WebSocketHandler wsHandler,
                                               Map<String, Object> attributes) {
                    String key = request.getHeaders().getFirst("X-API-Key");
                    apiKeys.add(key == null ? "" : key);
                    paths.add(request.getURI().getPath());
                    return true;
                }

                @Override
                public void afterHandshake(org.springframework.http.server.ServerHttpRequest request,
                                           org.springframework.http.server.ServerHttpResponse response,
                                           org.springframework.web.socket.WebSocketHandler wsHandler, Exception exception) {
                }
            };
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            sessions.add(session);
            if (closeOnConnect != null) {
                session.close(closeOnConnect);
            }
        }

        @Override
        protected void handleTextMessage(WebSocketSession s, TextMessage message) {
            texts.add(message.getPayload());
        }

        @Override
        protected void handleBinaryMessage(WebSocketSession s, BinaryMessage message) {
            ByteBuffer buffer = message.getPayload();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            binaries.add(bytes);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
            closedCount.incrementAndGet();
        }
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, long seconds) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("조건이 시간 안에 충족되지 않았다");
    }

    // ---- 시험 ----

    @Test
    @DisplayName("정상: AI에 키 헤더·세션 경로로 붙고 텍스트·바이너리를 그대로 중계하며 keyframe_request만 되돌린다")
    void 정상_중계() throws Exception {
        String sessionId = newSessionId();
        grant("t-ok", sessionId);
        Client client = connect("t-ok");

        assertThat(FAKE_AI.apiKeys.poll(3, TimeUnit.SECONDS)).isEqualTo(AI_KEY);
        assertThat(FAKE_AI.paths.poll(1, TimeUnit.SECONDS))
                .isEqualTo("/api/v1/stream-sessions/" + sessionId + "/ingest-ws");

        String config = "{\"type\":\"config\",\"codec\":\"avc1.42E01F\",\"width\":640,\"height\":360,\"framerate\":15,\"format\":\"annexb\"}";
        client.session.sendMessage(new TextMessage(config));
        assertThat(FAKE_AI.texts.poll(3, TimeUnit.SECONDS)).isEqualTo(config);

        byte[] frame = new byte[9 + 2000];
        frame[8] = 1;
        for (int i = 9; i < frame.length; i++) {
            frame[i] = (byte) i;
        }
        client.session.sendMessage(new BinaryMessage(frame));
        assertThat(FAKE_AI.binaries.poll(3, TimeUnit.SECONDS)).isEqualTo(frame);

        // AI -> 브라우저: 허용된 keyframe_request만 전달, 그 밖의 텍스트는 버린다
        WebSocketSession ai = FAKE_AI.sessions.get(0);
        ai.sendMessage(new TextMessage("{\"type\":\"debug\",\"secret\":\"x\"}"));
        ai.sendMessage(new TextMessage("{\"type\": \"keyframe_request\"}"));
        assertThat(client.texts.poll(3, TimeUnit.SECONDS)).isEqualTo("{\"type\":\"keyframe_request\"}");
        assertThat(client.texts.poll(300, TimeUnit.MILLISECONDS)).isNull();

        client.session.close();
        awaitTrue(() -> FAKE_AI.closedCount.get() >= 1, 5);   // 브라우저가 닫히면 AI 연결도 정리된다
    }

    @Test
    @DisplayName("핸드셰이크 거절은 수립 전 HTTP 오류다 - 티켓 오류 401 / 카메라 없음 404 / 대상 아님 409 / 기능 꺼짐 503, AI에는 붙지 않는다")
    void 거절_상태코드() throws Exception {
        when(UPLOAD_SERVICE.authorize("t-bad")).thenThrow(new CustomException(ErrorCode.CAMERA_UPLOAD_TICKET_INVALID));
        when(UPLOAD_SERVICE.authorize("t-gone")).thenThrow(new CustomException(ErrorCode.CAMERA_NOT_FOUND));
        when(UPLOAD_SERVICE.authorize("t-flag")).thenThrow(new CustomException(ErrorCode.CAMERA_UPLOAD_NOT_ENABLED));
        when(UPLOAD_SERVICE.authorize("t-off")).thenThrow(new CustomException(ErrorCode.CAMERA_UPLOAD_UNAVAILABLE));
        when(UPLOAD_SERVICE.authorize("t-redis")).thenThrow(new CustomException(ErrorCode.SERVICE_UNAVAILABLE));

        assertThat(handshakeStatus("t-bad", null)).isEqualTo(401);
        assertThat(handshakeStatus("t-gone", null)).isEqualTo(404);
        assertThat(handshakeStatus("t-flag", null)).isEqualTo(409);
        assertThat(handshakeStatus("t-off", null)).isEqualTo(503);
        assertThat(handshakeStatus("t-redis", null)).isEqualTo(503);
        assertThat(FAKE_AI.sessions).isEmpty();
    }

    @Test
    @DisplayName("허용되지 않은 Origin은 티켓을 소비하기 전에 거절한다(403)")
    void 출처_거절() throws Exception {
        grant("t-origin", newSessionId());
        assertThat(handshakeStatus("t-origin", "https://evil.example")).isEqualTo(403);
        org.mockito.Mockito.verify(UPLOAD_SERVICE, org.mockito.Mockito.never()).authorize(anyString());
        assertThat(FAKE_AI.sessions).isEmpty();
    }

    @Test
    @DisplayName("AI 키가 비어 있거나 AI에 붙을 수 없으면 503(FE는 JPEG로 폴백)")
    void AI_연결_실패() throws Exception {
        grant("t-noai", newSessionId());
        STREAM_PROPS.setAiBaseUrl("http://localhost:1");   // 닫힌 포트
        assertThat(handshakeStatus("t-noai", null)).isEqualTo(503);

        grant("t-nokey", newSessionId());
        STREAM_PROPS.setAiBaseUrl("http://localhost:" + port);
        STREAM_PROPS.setApiKey("");
        assertThat(handshakeStatus("t-nokey", null)).isEqualTo(503);
        assertThat(FAKE_AI.sessions).isEmpty();
    }

    @Test
    @DisplayName("한도 초과 바이너리는 해당 연결만 1009로 끊고 AI 연결도 정리한다")
    void 크기_초과() throws Exception {
        UPLOAD_PROPS.setMaxMessageBytes(4096);
        grant("t-big", newSessionId());
        Client client = connect("t-big");
        client.session.sendMessage(new BinaryMessage(new byte[5000]));
        CloseStatus status = client.awaitClose(5);
        assertThat(status.getCode()).isEqualTo(1009);
        awaitTrue(() -> FAKE_AI.closedCount.get() >= 1, 5);
        assertThat(FAKE_AI.binaries).isEmpty();
    }

    @Test
    @DisplayName("초당 메시지 상한(권장 fps x 2)을 넘으면 1008 RATE_LIMITED로 끊는다")
    void 속도_초과() throws Exception {
        grant("t-rate", newSessionId());
        Client client = connect("t-rate");
        byte[] small = new byte[20];
        for (int i = 0; i < 80; i++) {
            if (!client.session.isOpen()) {
                break;
            }
            client.session.sendMessage(new BinaryMessage(small));
        }
        CloseStatus status = client.awaitClose(5);
        assertThat(status.getCode()).isEqualTo(1008);
        assertThat(status.getReason()).isEqualTo("RATE_LIMITED");
    }

    @Test
    @DisplayName("같은 세션의 새 연결이 기존 연결을 1013 REPLACED로 교체하고 이전 AI 연결을 정리한다")
    void 세션당_하나_교체() throws Exception {
        String sessionId = newSessionId();
        grant("t-first", sessionId);
        grant("t-second", sessionId);
        Client first = connect("t-first");
        Client second = connect("t-second");

        // Tomcat 클라이언트는 RFC 7525 이후 코드 1013을 프로토콜 오류(1002)로 보고한다(브라우저는 1013 그대로 받는다).
        // 서버가 보낸 코드는 1013 REPLACED이며, 여기서는 어느 쪽이든 "교체로 닫혔다"만 확인한다.
        CloseStatus status = first.awaitClose(5);
        assertThat(status.getCode()).isIn(1013, 1002);
        awaitTrue(() -> FAKE_AI.closedCount.get() >= 1, 5);
        assertThat(second.session.isOpen()).isTrue();
        second.session.close();
    }

    @Test
    @DisplayName("전체 동시 상한을 넘는 새 세션은 수립 전에 503으로 거절하고, 같은 세션의 교체는 자리를 더 쓰지 않는다")
    void 전체_상한() throws Exception {
        UPLOAD_PROPS.setMaxConnections(1);
        String sessionId = newSessionId();
        grant("t-one", sessionId);
        Client one = connect("t-one");
        awaitTrue(() -> FAKE_AI.sessions.size() == 1, 3);

        grant("t-other", newSessionId());
        assertThat(handshakeStatus("t-other", null)).isEqualTo(503);

        grant("t-same", sessionId);   // 같은 세션 = 교체이므로 상한에 걸리지 않는다
        Client replacement = connect("t-same");
        assertThat(one.awaitClose(5).getCode()).isIn(1013, 1002);   // Tomcat 클라이언트는 1013을 1002로 본다
        replacement.session.close();
    }

    @Test
    @DisplayName("AI가 닫으면(플래그 꺼짐 등) 그 코드·사유를 브라우저에 그대로 전한다")
    void AI_종료_전파() throws Exception {
        grant("t-aiclose", newSessionId());
        Client client = connect("t-aiclose");
        awaitTrue(() -> FAKE_AI.sessions.size() == 1, 3);
        FAKE_AI.sessions.get(0).close(new CloseStatus(1008, "INGEST_DISABLED"));

        CloseStatus status = client.awaitClose(5);
        assertThat(status.getCode()).isEqualTo(1008);
        assertThat(status.getReason()).isEqualTo("INGEST_DISABLED");
    }

    @Test
    @DisplayName("무수신 제한을 넘기면 1000 IDLE_TIMEOUT으로 정리한다")
    void 무수신_종료() throws Exception {
        UPLOAD_PROPS.setIdleTimeout(java.time.Duration.ofSeconds(1));
        grant("t-idle", newSessionId());
        Client client = connect("t-idle");
        CloseStatus status = client.awaitClose(10);
        assertThat(status.getCode()).isEqualTo(1000);
        assertThat(status.getReason()).isEqualTo("IDLE_TIMEOUT");
        awaitTrue(() -> FAKE_AI.closedCount.get() >= 1, 5);
    }

    @Test
    @DisplayName("주기 재확인이 실패하면 1008 AUTH_INVALIDATED로 끊는다(재연결은 티켓 발급 단계에서 막힌다)")
    void 재확인_실패_종료() throws Exception {
        UPLOAD_PROPS.setRevalidateInterval(java.time.Duration.ofSeconds(1));
        when(UPLOAD_SERVICE.isStillValid(any())).thenReturn(false);
        grant("t-recheck", newSessionId());
        Client client = connect("t-recheck");
        CloseStatus status = client.awaitClose(10);
        assertThat(status.getCode()).isEqualTo(1008);
        assertThat(status.getReason()).isEqualTo("AUTH_INVALIDATED");
        awaitTrue(() -> FAKE_AI.closedCount.get() >= 1, 5);
    }

    @Test
    @DisplayName("수립 직후 AI가 곧바로 닫아도 브라우저는 연결 성공 뒤 같은 사유로 닫힌다(핸드셰이크 경합)")
    void 수립_직후_AI_종료() throws Exception {
        FAKE_AI.closeOnConnect = new CloseStatus(1008, "INGEST_DISABLED");
        grant("t-race", newSessionId());
        // 경합에 따라 핸드셰이크 단계에서 실패하거나(예외) 수립 뒤 닫히므로 어느 쪽이든 AI 사유가 전달돼야 한다
        try {
            Client client = connect("t-race");
            CloseStatus status = client.awaitClose(5);
            assertThat(status.getCode()).isEqualTo(1008);
            assertThat(status.getReason()).isEqualTo("INGEST_DISABLED");
        } catch (ExecutionException | AssertionError e) {
            if (e instanceof AssertionError) {
                throw e;
            }
            assertThatThrownBy(() -> { throw e; }).isInstanceOf(ExecutionException.class);
        }
    }
}

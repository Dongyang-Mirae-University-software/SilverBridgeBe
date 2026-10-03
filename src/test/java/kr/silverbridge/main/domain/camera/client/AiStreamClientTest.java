package kr.silverbridge.main.domain.camera.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kr.silverbridge.main.domain.camera.client.AiStreamClient.AiLiveStream;
import kr.silverbridge.main.domain.camera.client.AiStreamClient.AiMjpegStream;
import kr.silverbridge.main.domain.camera.config.CameraStreamProperties;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI REST 클라이언트 - 실제 HTTP로 키 헤더·응답 해석·404/장애 구분을 확인한다(로컬 임시 서버).
 */
class AiStreamClientTest {

    private static final String KEY = "test-key";

    private HttpServer server;
    private final AtomicReference<String> receivedKey = new AtomicReference<>();
    private final AtomicReference<String> receivedQuery = new AtomicReference<>();
    private final AtomicReference<String> receivedRawPath = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean redirectFollowed = new java.util.concurrent.atomic.AtomicBoolean();
    private CameraStreamProperties properties;
    private final org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler =
            new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/live-streams", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        properties = new CameraStreamProperties();
        properties.setAiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey(KEY);
        properties.setReadTimeout(Duration.ofSeconds(2));
        properties.setStreamIdleTimeout(Duration.ofSeconds(2));
        scheduler.initialize();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        scheduler.shutdown();
    }

    private void handle(HttpExchange exchange) throws IOException {
        receivedKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
        receivedQuery.set(exchange.getRequestURI().getRawQuery());
        receivedRawPath.set(exchange.getRequestURI().getRawPath());
        String path = exchange.getRequestURI().getPath();
        switch (path) {
            case "/api/v1/live-streams" -> json(exchange, 200, """
                    {"success":true,"data":[
                      {"sessionId":"s1","status":"running","lastFrameAt":"2026-10-03T05:03:09.123456"},
                      {"sessionId":"s2","status":"disconnected","lastFrameAt":null}]}""");
            case "/api/v1/live-streams/s1/status" -> json(exchange, 200, """
                    {"success":true,"data":{"sessionId":"s1","status":"running","fps":1.9,"viewerCount":3,"isAnalyzing":true}}""");
            case "/api/v1/live-streams/gone/status", "/api/v1/live-streams/gone/mjpeg" ->
                    json(exchange, 404, "{\"success\":false,\"errorCode\":\"STREAM_SESSION_NOT_FOUND\"}");
            case "/api/v1/live-streams/boom/status" -> json(exchange, 500, "{}");
            case "/api/v1/live-streams/slowhead/status" -> {
                // 헤더를 늦게 준다 - 읽기 제한(2초) 안이지만 전체 제한(1초)은 넘는다
                sleepQuietly(1_500);
                json(exchange, 200, "{\"success\":true,\"data\":{\"status\":\"running\"}}");
            }
            case "/api/v1/live-streams/trickle/latest-frame" -> {
                // 본문을 조금씩 흘린다 - 읽기 1회 제한(2초)에는 한 번도 걸리지 않는다
                exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    for (int i = 0; i < 50; i++) {
                        out.write(0xFF);
                        out.flush();
                        sleepQuietly(200);
                    }
                } catch (IOException ignored) {
                    // 클라이언트가 끊음
                }
            }
            case "/api/v1/live-streams/moved/status", "/api/v1/live-streams/moved/mjpeg" -> {
                exchange.getResponseHeaders().set("Location", "/api/v1/live-streams/target/status");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            }
            case "/api/v1/live-streams/target/status" -> {
                redirectFollowed.set(true);
                json(exchange, 200, "{\"success\":true,\"data\":{\"status\":\"running\"}}");
            }
            case "/api/v1/live-streams/endless/mjpeg" -> {
                // 실제 AI처럼 끝나지 않는 MJPEG - 클라이언트가 끊을 때까지 계속 보낸다
                exchange.getResponseHeaders().set("Content-Type", "multipart/x-mixed-replace; boundary=frame");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    while (true) {
                        out.write("--frame\r\nContent-Type: image/jpeg\r\n\r\nJPEG\r\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        Thread.sleep(20);
                    }
                } catch (IOException | InterruptedException ignored) {
                    // 클라이언트가 끊음
                }
            }
            case "/api/v1/live-streams/s1/mjpeg" -> {
                exchange.getResponseHeaders().set("Content-Type", "multipart/x-mixed-replace; boundary=frame");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write("--frame\r\n".getBytes(StandardCharsets.US_ASCII));
                }
            }
            default -> json(exchange, 404, "{}");
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private AiStreamClient client() {
        return new AiStreamClient(properties, new ObjectMapper(), scheduler);
    }

    @Test
    @DisplayName("목록: 키는 X-API-Key 헤더로만 보내고(쿼리 없음) 세션별 상태·시각(UTC → KST)을 읽는다")
    void 목록() {
        Map<String, AiLiveStream> streams = client().fetchLiveStreams();

        assertThat(receivedKey.get()).isEqualTo(KEY);
        assertThat(receivedQuery.get()).isNull();
        assertThat(streams).containsOnlyKeys("s1", "s2");
        assertThat(streams.get("s1").status()).isEqualTo("running");
        // AI의 오프셋 없는 UTC를 KST로 바꿔 싣는다(점검 I-3 - 분석 시각과 오프셋을 맞춘다)
        assertThat(streams.get("s1").lastFrameAt())
                .isEqualTo(OffsetDateTime.of(2026, 10, 3, 14, 3, 9, 123_456_000, ZoneOffset.ofHours(9)));
        assertThat(streams.get("s2").lastFrameAt()).isNull();
    }

    @Test
    @DisplayName("상태: AI가 세션을 모르면(404) 빈 값, 5xx는 장애 - 둘을 섞지 않는다")
    void 상태_404와_장애구분() {
        AiStreamClient client = client();

        assertThat(client.fetchStatus("s1")).hasValueSatisfying(s -> {
            assertThat(s.status()).isEqualTo("running");
            assertThat(s.fps()).isEqualTo(1.9);
            assertThat(s.isAnalyzing()).isTrue();
        });
        assertThat(client.fetchStatus("gone")).isEmpty();
        assertThatThrownBy(() -> client.fetchStatus("boom")).isInstanceOf(AiStreamUnavailableException.class);
    }

    @Test
    @DisplayName("MJPEG: 열어서 본문을 읽을 수 있고, 세션이 없으면 404 CAMERA_NOT_STREAMING")
    void mjpeg() throws IOException {
        AiStreamClient client = client();

        try (AiMjpegStream stream = client.openMjpeg("s1")) {
            assertThat(stream.contentType()).startsWith("multipart/x-mixed-replace");
            assertThat(new String(stream.body().readAllBytes(), StandardCharsets.US_ASCII)).isEqualTo("--frame\r\n");
        }
        assertThatThrownBy(() -> client.openMjpeg("gone"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CAMERA_NOT_STREAMING);
    }

    @Test
    @DisplayName("끝나지 않는 MJPEG도 즉시 닫힌다 - 본문을 끝까지 읽지(drain) 않는다 (시청자 이탈 시 스레드·자리 누수 방지)")
    void 끝없는스트림_즉시닫힘() throws Exception {
        AiMjpegStream stream = client().openMjpeg("endless");
        assertThat(stream.body().read(new byte[64])).isPositive();

        java.util.concurrent.CompletableFuture<Void> closing =
                java.util.concurrent.CompletableFuture.runAsync(stream::close);

        closing.get(2, java.util.concurrent.TimeUnit.SECONDS);   // drain하면 여기서 시간 초과
    }

    @Test
    @DisplayName("다른 스레드에서 닫으면 읽고 있던 쪽이 예외로 빠져나온다 (서버 종료 처리)")
    void 다른스레드에서_닫기() throws Exception {
        AiMjpegStream stream = client().openMjpeg("endless");
        java.util.concurrent.CompletableFuture<Boolean> reader = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            byte[] buffer = new byte[1024];
            try {
                while (stream.body().read(buffer) >= 0) {
                    // 계속 읽는다
                }
                return true;
            } catch (IOException e) {
                return true;
            }
        });
        Thread.sleep(100);

        stream.close();

        assertThat(reader.get(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("리다이렉트를 따라가지 않고 장애로 본다 - 30x로 다른 곳을 가리켜도 AI 키가 따라가지 않게 (점검 L-2)")
    void 리다이렉트_미추종() {
        AiStreamClient client = client();

        assertThatThrownBy(() -> client.fetchStatus("moved")).isInstanceOf(AiStreamUnavailableException.class);
        assertThatThrownBy(() -> client.openMjpeg("moved")).isInstanceOf(AiStreamUnavailableException.class);
        assertThat(redirectFollowed).isFalse();
    }

    @Test
    @DisplayName("헤더가 늦으면 호출 전체 제한에서 끊는다(읽기 1회 제한보다 짧아도) (점검 L-3)")
    void 전체시간제한_헤더지연() {
        properties.setCallTimeout(Duration.ofSeconds(1));
        long started = System.nanoTime();

        assertThatThrownBy(() -> client().fetchStatus("slowhead")).isInstanceOf(AiStreamUnavailableException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_400));
    }

    @Test
    @DisplayName("본문을 조금씩 흘려도 호출 전체 제한에서 끊는다 - 읽기 1회 제한으로는 걸리지 않는 경우 (점검 L-3)")
    void 전체시간제한_본문흘림() {
        properties.setCallTimeout(Duration.ofSeconds(1));
        long started = System.nanoTime();

        assertThatThrownBy(() -> client().fetchLatestFrame("trickle")).isInstanceOf(AiStreamUnavailableException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2_000));
    }

    @Test
    @DisplayName("AI 키가 비어 있으면 호출하지 않고 장애로 본다(기동은 막지 않는다)")
    void 키없음() {
        properties.setApiKey("");

        assertThatThrownBy(() -> client().fetchLiveStreams()).isInstanceOf(AiStreamUnavailableException.class);
        assertThat(receivedKey.get()).isNull();
    }

    @Test
    @DisplayName("AI 서버가 꺼져 있으면 장애(연결 실패)")
    void 서버꺼짐() {
        server.stop(0);

        assertThatThrownBy(() -> client().fetchLiveStreams()).isInstanceOf(AiStreamUnavailableException.class);
    }

    @Test
    @DisplayName("세션 ID의 '/'까지 인코딩해 한 경로 조각으로만 넣는다(다른 AI 경로를 가리킬 수 없다)")
    void 경로인코딩() {
        client().fetchStatus("../../stream-sessions/x");

        assertThat(receivedRawPath.get()).isEqualTo("/api/v1/live-streams/..%2F..%2Fstream-sessions%2Fx/status");
    }
}

package kr.silverbridge.main.domain.anomaly.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kr.silverbridge.main.domain.anomaly.client.AiClipClient.ClipResult;
import kr.silverbridge.main.domain.anomaly.client.AiClipClient.Outcome;
import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.camera.config.CameraStreamProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 클립 클라이언트 - 계약서(2026-10-04 최종본) 모양의 모의 AI 서버로 요청 형식·응답 검증·오류 매핑을 확인한다.
 * AI 서버의 실제 클립 API가 배포되기 전이라 이 테스트가 계약의 소비자 쪽 고정점이다.
 */
class AiClipClientTest {

    private static final String KEY = "test-key";
    private static final byte[] WEBM = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0x01, 0x02, 0x03};

    private HttpServer server;
    private final AtomicReference<String> receivedKey = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedMethod = new AtomicReference<>();
    private final AtomicReference<String> receivedRawPath = new AtomicReference<>();
    private final AtomicBoolean redirectFollowed = new AtomicBoolean();
    private final AtomicBoolean called = new AtomicBoolean();
    private CameraStreamProperties streamProperties;
    private AnomalyProperties anomalyProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/live-streams", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        scheduler.initialize();
        streamProperties = new CameraStreamProperties();
        streamProperties.setAiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        streamProperties.setApiKey(KEY);
        anomalyProperties = new AnomalyProperties();
        anomalyProperties.getClip().setRequestTimeout(Duration.ofSeconds(1));
        anomalyProperties.getClip().setMaxBytes(64);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        scheduler.shutdown();
    }

    private AiClipClient client() {
        return new AiClipClient(streamProperties, anomalyProperties, objectMapper, scheduler);
    }

    private void handle(HttpExchange exchange) throws IOException {
        called.set(true);
        receivedKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
        receivedMethod.set(exchange.getRequestMethod());
        receivedRawPath.set(exchange.getRequestURI().getRawPath());
        receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String session = exchange.getRequestURI().getPath()
                .replace("/api/v1/live-streams/", "").replace("/clips", "");
        switch (session) {
            case "ok" -> {
                exchange.getResponseHeaders().set("Content-Type", "video/webm");
                exchange.getResponseHeaders().set("X-Clip-Duration-Ms", "5000");
                exchange.getResponseHeaders().set("X-Clip-Frames", "25");
                exchange.getResponseHeaders().set("X-Clip-Width", "1920");
                exchange.getResponseHeaders().set("X-Clip-Height", "1080");
                exchange.getResponseHeaders().set("X-Clip-Started-At", "2026-10-04T01:00:00Z");
                bytes(exchange, 200, WEBM);
            }
            case "nometa" -> {
                exchange.getResponseHeaders().set("X-Clip-Width", "wide");
                exchange.getResponseHeaders().set("X-Clip-Started-At", "2026-10-04T01:00:00.123456");
                bytes(exchange, 200, WEBM);
            }
            case "notwebm" -> bytes(exchange, 200, "<html>proxy error</html>".getBytes(StandardCharsets.UTF_8));
            case "huge" -> {
                byte[] big = Arrays.copyOf(WEBM, 65);
                bytes(exchange, 200, big);
            }
            case "drip" -> {
                // 바이트를 0.3초마다 하나씩 - 읽기 1회 제한(1초)에는 안 걸리지만 전체는 3초 넘게 걸린다
                exchange.getResponseHeaders().set("Content-Type", "video/webm");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    for (int i = 0; i < 12; i++) {
                        out.write(WEBM[i % WEBM.length]);
                        out.flush();
                        sleep(300);
                    }
                } catch (IOException ignored) {
                    // 클라이언트가 마감으로 끊었다
                }
            }
            case "slow" -> {
                sleep(2_000);
                bytes(exchange, 200, WEBM);
            }
            case "moved" -> {
                exchange.getResponseHeaders().set("Location", "/api/v1/live-streams/target/clips");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            }
            case "target" -> {
                redirectFollowed.set(true);
                bytes(exchange, 200, WEBM);
            }
            case "e409" -> error(exchange, 409, "CLIP_NOT_ENOUGH_FRAMES");
            case "e404" -> error(exchange, 404, "STREAM_SESSION_NOT_FOUND");
            case "e429" -> error(exchange, 429, "CLIP_BUSY");
            case "e500" -> error(exchange, 500, "CLIP_ENCODE_FAILED");
            case "e401" -> error(exchange, 401, "AUTH_INVALID_KEY");
            case "e422" -> error(exchange, 422, "CLIP_INVALID_PARAMS");
            case "e502" -> bytes(exchange, 502, "bad gateway".getBytes(StandardCharsets.UTF_8));
            default -> error(exchange, 404, "STREAM_SESSION_NOT_FOUND");
        }
    }

    private static void error(HttpExchange exchange, int status, String code) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        bytes(exchange, status, ("{\"success\":false,\"message\":\"x\",\"errorCode\":\"" + code + "\",\"data\":null}")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void bytes(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("정상 - POST + 키 헤더 + 계약 형식 본문, WebM 바이트와 헤더 메타를 돌려준다")
    void 정상_요청과_응답() throws IOException {
        OffsetDateTime detectedAt = OffsetDateTime.of(2026, 10, 4, 10, 0, 5, 0, ZoneOffset.ofHours(9));

        ClipResult result = client().requestClip("ok", detectedAt);

        assertThat(result.isOk()).isTrue();
        assertThat(result.body()).isEqualTo(WEBM);
        assertThat(result.meta().durationMs()).isEqualTo(5000);
        assertThat(result.meta().frames()).isEqualTo(25);
        assertThat(result.meta().width()).isEqualTo(1920);
        assertThat(result.meta().height()).isEqualTo(1080);
        assertThat(result.meta().startedAt()).isEqualTo(OffsetDateTime.parse("2026-10-04T01:00:00Z"));
        assertThat(receivedMethod.get()).isEqualTo("POST");
        assertThat(receivedKey.get()).isEqualTo(KEY);
        JsonNode body = objectMapper.readTree(receivedBody.get());
        assertThat(body.path("detectedAt").asText()).as("UTC Z 형식").isEqualTo("2026-10-04T01:00:05Z");
        assertThat(body.path("preSeconds").asDouble()).isEqualTo(3.0);
        assertThat(body.path("postSeconds").asDouble()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("감지 시각이 없으면 detectedAt을 보내지 않는다(AI가 수신 시각을 쓴다)")
    void 감지시각_없으면_생략() throws IOException {
        client().requestClip("ok", null);

        assertThat(objectMapper.readTree(receivedBody.get()).has("detectedAt")).isFalse();
    }

    @Test
    @DisplayName("헤더가 없거나 형식이 틀리면 그 값은 null(0으로 채우지 않는다), 오프셋 없는 시각은 UTC로 읽는다")
    void 헤더_누락은_null() {
        ClipResult result = client().requestClip("nometa", null);

        assertThat(result.isOk()).isTrue();
        assertThat(result.meta().durationMs()).isNull();
        assertThat(result.meta().width()).isNull();
        assertThat(result.meta().startedAt()).isEqualTo(OffsetDateTime.parse("2026-10-04T01:00:00.123456Z"));
    }

    @Test
    @DisplayName("200이어도 WebM(EBML) 시그니처가 아니면 저장하지 않는다")
    void 시그니처_불일치_거부() {
        assertThat(client().requestClip("notwebm", null).outcome()).isEqualTo(Outcome.INVALID_RESPONSE);
    }

    @Test
    @DisplayName("크기 상한을 넘으면 저장하지 않는다")
    void 크기_상한_초과() {
        assertThat(client().requestClip("huge", null).outcome()).isEqualTo(Outcome.TOO_LARGE);
    }

    @Test
    @DisplayName("응답 제한을 넘으면 UNAVAILABLE(재시도 가능)")
    void 응답_시간초과() {
        ClipResult result = client().requestClip("slow", null);

        assertThat(result.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(result.outcome().isRetryable()).isTrue();
    }

    @Test
    @DisplayName("본문을 조금씩 흘려 보내도 호출 전체 제한에서 끊는다(읽기 1회 제한만으로는 무한정 묶인다 - L-3과 같은 기준)")
    void 전체_시간제한() {
        long started = System.nanoTime();

        ClipResult result = client().requestClip("drip", null);

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertThat(result.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        // 전체 제한 1초 + 읽기 1회(0.3초 간격) 여유 - 서버가 다 보내는 3.6초보다 짧아야 한다
        assertThat(elapsedMillis).isLessThan(2_500);
    }

    @Test
    @DisplayName("리다이렉트는 따라가지 않는다(키 헤더 유출 방지) - 장애로 본다")
    void 리다이렉트_미추종() {
        ClipResult result = client().requestClip("moved", null);

        assertThat(result.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(redirectFollowed).isFalse();
    }

    @ParameterizedTest(name = "{0} → {1}, retryable={2}")
    @CsvSource({
            "e409, NOT_ENOUGH_FRAMES, true",
            "e404, SESSION_NOT_FOUND, true",
            "e429, BUSY, true",
            "e500, SERVER_ERROR, true",
            "e502, SERVER_ERROR, true",
            "e401, REJECTED, false",
            "e422, REJECTED, false"
    })
    @DisplayName("계약서 오류 응답을 결과 코드로 바꾼다 - 키·파라미터 결함만 재시도하지 않는다")
    void 오류_매핑(String session, Outcome expected, boolean retryable) {
        ClipResult result = client().requestClip(session, null);

        assertThat(result.outcome()).isEqualTo(expected);
        assertThat(result.outcome().isRetryable()).isEqualTo(retryable);
        assertThat(result.body()).isNull();
    }

    @Test
    @DisplayName("오류 본문의 errorCode를 꺼낸다(로그용)")
    void 오류코드_추출() {
        assertThat(client().requestClip("e429", null).errorCode()).isEqualTo("CLIP_BUSY");
    }

    @Test
    @DisplayName("AI 키가 없으면 요청하지 않는다(재시도 안 함)")
    void 키_미설정() {
        streamProperties.setApiKey("");

        ClipResult result = client().requestClip("ok", null);

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_CONFIGURED);
        assertThat(result.outcome().isRetryable()).isFalse();
        assertThat(called).isFalse();
    }

    @Test
    @DisplayName("세션 ID의 '/'는 인코딩돼 다른 AI 경로를 가리키지 못한다")
    void 세션ID_경로_인코딩() {
        client().requestClip("a/../../health", null);

        assertThat(receivedRawPath.get()).isEqualTo("/api/v1/live-streams/a%2F..%2F..%2Fhealth/clips");
    }
}

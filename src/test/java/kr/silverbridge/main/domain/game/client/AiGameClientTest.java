package kr.silverbridge.main.domain.game.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kr.silverbridge.main.domain.game.config.GameRelayProperties;
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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AI 게임 기록 클라이언트 - 실제 HTTP(로컬 임시 서버)로 키 헤더·쿼리·실패 처리를 확인한다. */
class AiGameClientTest {

    private static final String KEY = "test-key";

    private HttpServer server;
    private final AtomicReference<String> receivedKey = new AtomicReference<>();
    private final AtomicReference<String> receivedQuery = new AtomicReference<>();
    private final AtomicBoolean redirectFollowed = new AtomicBoolean();
    private volatile Handler progressHandler;
    private GameRelayProperties properties;
    private AiGameClient client;

    interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/games/progress", ex -> {
            record(ex);
            progressHandler.handle(ex);
        });
        server.createContext("/api/v1/games/activity", ex -> {
            record(ex);
            json(ex, 200, "{\"success\":true,\"data\":{\"userId\":1,\"days\":30,\"activity\":[]}}");
        });
        server.createContext("/elsewhere", ex -> {
            redirectFollowed.set(true);
            json(ex, 200, "{\"success\":true,\"data\":{}}");
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        properties = new GameRelayProperties();
        properties.setAiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey(KEY);
        properties.setReadTimeout(Duration.ofSeconds(2));
        properties.setCallTimeout(Duration.ofSeconds(1));
        client = new AiGameClient(properties, new ObjectMapper());
        progressHandler = ex -> json(ex, 200, "{\"success\":true,\"data\":{\"userId\":1455350745,\"games\":[]}}");
    }

    @AfterEach
    void tearDown() {
        client.shutdown();
        server.stop(0);
    }

    private void record(HttpExchange ex) {
        receivedKey.set(ex.getRequestHeaders().getFirst("X-API-Key"));
        receivedQuery.set(ex.getRequestURI().getRawQuery());
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void assertUnavailable(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.GAME_UNAVAILABLE);
    }

    @Test
    @DisplayName("진행 조회: 키는 X-API-Key 헤더로만, 쿼리엔 userId만 싣고 data 를 돌려준다")
    void progressSendsKeyHeader() {
        JsonNode data = client.fetchProgress(1455350745L);

        assertThat(data.path("userId").asLong()).isEqualTo(1455350745L);
        assertThat(receivedKey.get()).isEqualTo(KEY);
        assertThat(receivedQuery.get()).isEqualTo("userId=1455350745");
    }

    @Test
    @DisplayName("활동 조회: userId·days 쿼리")
    void activityQuery() {
        client.fetchActivity(2_147_483_648L, 30);

        assertThat(receivedQuery.get()).isEqualTo("userId=2147483648&days=30");
    }

    @Test
    @DisplayName("AI 5xx → 503 GAME_UNAVAILABLE")
    void serverError() {
        progressHandler = ex -> json(ex, 500, "{}");
        assertUnavailable(() -> client.fetchProgress(1));
    }

    @Test
    @DisplayName("AI 401(키 불일치) → 503 GAME_UNAVAILABLE (보호자 401로 내보내지 않는다)")
    void keyRejected() {
        progressHandler = ex -> json(ex, 401, "{\"success\":false,\"errorCode\":\"AUTH_INVALID_KEY\"}");
        assertUnavailable(() -> client.fetchProgress(1));
    }

    @Test
    @DisplayName("호출 전체 제한(1초) 초과 → 503")
    void callTimeout() {
        progressHandler = ex -> {
            try {
                Thread.sleep(1_500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            json(ex, 200, "{\"success\":true,\"data\":{}}");
        };
        assertUnavailable(() -> client.fetchProgress(1));
    }

    @Test
    @DisplayName("리다이렉트를 따라가지 않는다(키 헤더가 다른 주소로 가지 않게) → 503")
    void noRedirect() {
        progressHandler = ex -> {
            ex.getResponseHeaders().add("Location", properties.getAiBaseUrl() + "/elsewhere");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        };
        assertUnavailable(() -> client.fetchProgress(1));
        assertThat(redirectFollowed).isFalse();
    }

    @Test
    @DisplayName("JSON 이 아니거나 data 가 객체가 아니면 → 503")
    void malformed() {
        progressHandler = ex -> json(ex, 200, "<html>not json</html>");
        assertUnavailable(() -> client.fetchProgress(1));

        progressHandler = ex -> json(ex, 200, "{\"success\":true,\"data\":null}");
        assertUnavailable(() -> client.fetchProgress(1));
    }

    @Test
    @DisplayName("응답 크기 상한 초과 → 503")
    void oversized() {
        String big = "{\"success\":true,\"data\":{\"pad\":\"" + "x".repeat(AiGameClient.MAX_JSON_BYTES) + "\"}}";
        progressHandler = ex -> json(ex, 200, big);
        assertUnavailable(() -> client.fetchProgress(1));
    }

    @Test
    @DisplayName("키 미설정 → AI 를 부르지 않고 503")
    void missingKey() {
        properties.setApiKey("");
        assertUnavailable(() -> client.fetchProgress(1));
        assertThat(receivedKey.get()).isNull();
    }
}

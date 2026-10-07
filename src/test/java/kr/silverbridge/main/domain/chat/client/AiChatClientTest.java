package kr.silverbridge.main.domain.chat.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kr.silverbridge.main.domain.chat.config.ChatRelayProperties;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AI 챗 클라이언트 - 실제 HTTP(로컬 임시 서버)로 키 헤더·오류 매핑·시간 제한·리다이렉트 미추종을 확인한다. */
class AiChatClientTest {

    private static final String KEY = "test-key";

    private HttpServer server;
    private final AtomicReference<String> receivedKey = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedRawUri = new AtomicReference<>();
    private final AtomicBoolean redirectFollowed = new AtomicBoolean();
    private ChatRelayProperties properties;
    private AiChatClient client;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/chat", this::handle);
        server.createContext("/elsewhere", ex -> {
            redirectFollowed.set(true);
            json(ex, 200, "{\"data\":{}}");
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        properties = new ChatRelayProperties();
        properties.setAiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey(KEY);
        scheduler.initialize();
        client = new AiChatClient(properties, new ObjectMapper(), scheduler);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        scheduler.shutdown();
    }

    private void handle(HttpExchange ex) throws IOException {
        receivedKey.set(ex.getRequestHeaders().getFirst("X-API-Key"));
        receivedRawUri.set(ex.getRequestURI().getRawPath() + "?" + ex.getRequestURI().getRawQuery());
        receivedBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String path = ex.getRequestURI().getPath();
        String body = receivedBody.get();
        if (path.equals("/api/v1/chat") && body.contains("BAD400")) {
            json(ex, 400, "{\"detail\":{\"message\":\"비밀 내용 포함 AI 오류 문구\"}}");
        } else if (path.equals("/api/v1/chat") && body.contains("BAD422")) {
            json(ex, 422, "{}");
        } else if (path.equals("/api/v1/chat") && body.contains("BOOM")) {
            json(ex, 500, "{}");
        } else if (path.equals("/api/v1/chat") && body.contains("SLOW")) {
            sleepQuietly(1_500);
            json(ex, 200, "{\"data\":{\"reply\":\"late\"}}");
        } else if (path.equals("/api/v1/chat") && body.contains("REDIRECT")) {
            ex.getResponseHeaders().set("Location", "/elsewhere");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        } else if (path.equals("/api/v1/chat")) {
            json(ex, 200, "{\"success\":true,\"message\":\"ok\",\"data\":{\"reply\":\"안녕하세요\",\"riskLevel\":\"low\"}}");
        } else if (path.equals("/api/v1/chat/logs")) {
            json(ex, 200, "{\"success\":true,\"data\":[{\"id\":1},{\"id\":2}]}");
        } else if (path.equals("/api/v1/chat/logs/7")) {
            json(ex, 200, "{\"success\":true,\"data\":{\"id\":7}}");
        } else {
            json(ex, 404, "{\"detail\":{\"errorCode\":\"CHAT_LOG_NOT_FOUND\"}}");
        }
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] req(String text) {
        return ("{\"message\":\"" + text + "\",\"userId\":\"U00001\"}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("전송 정상 - X-API-Key 헤더로만 키를 보내고 AI 응답의 data를 돌려준다")
    void send_정상() {
        JsonNode data = client.send(req("hello"));

        assertThat(data.path("reply").asText()).isEqualTo("안녕하세요");
        assertThat(receivedKey.get()).isEqualTo(KEY);
        assertThat(receivedBody.get()).contains("\"message\":\"hello\"");
        assertThat(receivedRawUri.get()).doesNotContain(KEY);
    }

    @Test
    @DisplayName("AI 400·422 → CHAT_INVALID_REQUEST(AI 오류 문구는 옮기지 않는다)")
    void send_AI400_422() {
        assertThatThrownBy(() -> client.send(req("BAD400")))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_INVALID_REQUEST);
                    assertThat(e.getMessage()).doesNotContain("비밀");
                });
        assertThatThrownBy(() -> client.send(req("BAD422")))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_INVALID_REQUEST));
    }

    @Test
    @DisplayName("AI 5xx → CHAT_UNAVAILABLE")
    void send_AI5xx() {
        assertThatThrownBy(() -> client.send(req("BOOM")))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_UNAVAILABLE));
    }

    @Test
    @DisplayName("호출 전체 시간 제한을 넘기면 CHAT_TIMEOUT")
    void send_시간초과() {
        properties.setCallTimeout(Duration.ofMillis(500));

        assertThatThrownBy(() -> client.send(req("SLOW")))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_TIMEOUT));
    }

    @Test
    @DisplayName("리다이렉트는 따라가지 않는다(키 헤더 유출 방지)")
    void send_리다이렉트미추종() {
        assertThatThrownBy(() -> client.send(req("REDIRECT")))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_UNAVAILABLE));
        assertThat(redirectFollowed).isFalse();
    }

    @Test
    @DisplayName("키 미설정 → CHAT_UNAVAILABLE, AI를 호출하지 않는다")
    void send_키미설정() {
        properties.setApiKey("");
        receivedKey.set(null);

        assertThatThrownBy(() -> client.send(req("hello")))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_UNAVAILABLE));
        assertThat(receivedKey.get()).isNull();
    }

    @Test
    @DisplayName("기록 목록·상세 - userId는 쿼리로 인코딩되어 나간다(특수문자로 쿼리를 확장할 수 없다)")
    void logs_userId_인코딩() {
        assertThat(client.logs("U00001")).hasSize(2);
        assertThat(receivedRawUri.get()).isEqualTo("/api/v1/chat/logs?userId=U00001");

        client.logs("a&userId=other");
        assertThat(receivedRawUri.get()).doesNotContain("&userId=other");

        assertThat(client.logDetail("U00001", 7).orElseThrow().path("id").asInt()).isEqualTo(7);
        assertThat(receivedRawUri.get()).isEqualTo("/api/v1/chat/logs/7?userId=U00001");
    }

    @Test
    @DisplayName("기록 상세 404 → 빈 값(없는 기록과 남의 기록을 구분하지 않는다)")
    void logDetail_404() {
        assertThat(client.logDetail("U00001", 999)).isEmpty();
    }
}

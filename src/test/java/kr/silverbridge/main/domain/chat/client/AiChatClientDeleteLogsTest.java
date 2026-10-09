package kr.silverbridge.main.domain.chat.client;

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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 탈퇴 정리용 AI 챗 기록 삭제 호출(2026-10-09) - 메서드·경로·키 헤더·인코딩과 "성공은 200뿐"을 실제 HTTP로 확인한다. */
class AiChatClientDeleteLogsTest {

    private static final String KEY = "test-key";

    private HttpServer server;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> key = new AtomicReference<>();
    private final AtomicReference<String> rawUri = new AtomicReference<>();
    private volatile int responseStatus = 200;
    private ChatRelayProperties properties;
    private AiChatClient client;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/chat/logs", this::handle);
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
        method.set(ex.getRequestMethod());
        key.set(ex.getRequestHeaders().getFirst("X-API-Key"));
        rawUri.set(ex.getRequestURI().getRawPath() + "?" + ex.getRequestURI().getRawQuery());
        byte[] body = "{\"success\":true,\"data\":{\"deleted\":2}}".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(responseStatus, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    @Test
    @DisplayName("DELETE /api/v1/chat/logs?userId= 로 키 헤더와 함께 보낸다")
    void 삭제_요청_형태() {
        assertThatNoException().isThrownBy(() -> client.deleteLogs("aB3x9Z"));

        assertThat(method.get()).isEqualTo("DELETE");
        assertThat(key.get()).isEqualTo(KEY);
        assertThat(rawUri.get()).isEqualTo("/api/v1/chat/logs?userId=aB3x9Z");
    }

    @Test
    @DisplayName("userId는 엄격히 인코딩된다 - 다른 경로·쿼리를 가리킬 수 없다")
    void userId_인코딩() {
        client.deleteLogs("a&userId=VICTIM/../x");

        assertThat(rawUri.get()).startsWith("/api/v1/chat/logs?userId=").doesNotContain("&userId=VICTIM");
    }

    @Test
    @DisplayName("200이 아니면 성공으로 보지 않는다 - 404·405(옛 AI 서버)·422·500 모두 예외")
    void 비정상_응답은_실패() {
        for (int status : new int[]{404, 405, 422, 500}) {
            responseStatus = status;
            assertThatThrownBy(() -> client.deleteLogs("aB3x9Z"))
                    .as("status %d", status)
                    .isInstanceOfSatisfying(CustomException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_UNAVAILABLE));
        }
    }

    @Test
    @DisplayName("AI 키가 없으면 호출하지 않고 실패한다")
    void 키_미설정() {
        properties.setApiKey("");
        method.set(null);

        assertThatThrownBy(() -> client.deleteLogs("aB3x9Z")).isInstanceOf(CustomException.class);
        assertThat(method.get()).isNull();
    }
}

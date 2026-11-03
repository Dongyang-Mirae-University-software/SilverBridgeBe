package kr.silverbridge.main.domain.companion.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kr.silverbridge.main.domain.companion.config.CompanionProperties;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 말벗 AI 클라이언트 - 실제 HTTP(JDK HttpServer)로 키 헤더·응답 풀기·오류 코드 변환·시간 제한·multipart를 본다. */
class AiCompanionClientTest {

    private HttpServer server;
    private AiCompanionClient client;
    private CompanionProperties properties;
    private final AtomicReference<String> lastKey = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<byte[]> lastBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "{}";
    private volatile long delayMillis = 0;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        properties = new CompanionProperties();
        properties.setEnabled(true);
        properties.setAiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("test-companion-key");
        properties.setCallTimeout(Duration.ofSeconds(2));
        client = new AiCompanionClient(properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        client.shutdown();
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
        lastPath.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
        lastBody.set(exchange.getRequestBody().readAllBytes());
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        } catch (IOException ignored) {
            // 클라이언트가 마감으로 끊은 경우
        }
    }

    @Test
    @DisplayName("대화 열기: 키는 헤더로, sessionId·questionId는 따로 보내고 data만 돌려준다")
    void openDialogue() {
        responseBody = "{\"success\":true,\"message\":\"ok\",\"data\":{\"dialogueId\":\"d-1\",\"maxTurns\":8}}";

        JsonNode data = client.openDialogue("CW0001", 42L, "09:00", "지난밤에는 잠을 어떻게 주무셨어요?", 7L);

        assertThat(data.path("dialogueId").asText()).isEqualTo("d-1");
        assertThat(lastKey.get()).isEqualTo("test-companion-key");
        assertThat(lastPath.get()).isEqualTo("POST /companion/dialogues");
        String sent = new String(lastBody.get(), StandardCharsets.UTF_8);
        assertThat(sent).contains("\"sessionId\":\"42\"").contains("\"questionId\":\"7\"").contains("\"slot\":\"09:00\"");
    }

    @Test
    @DisplayName("AI 오류 코드는 허용 목록으로만 바꾼다 - 대화 없음은 끊긴 대화, 끝난 대화는 종료")
    void mapsKnownCodes() {
        status = 404;
        responseBody = "{\"success\":false,\"code\":\"DIALOGUE_NOT_FOUND\",\"errorCode\":\"DIALOGUE_NOT_FOUND\"}";
        assertThatThrownBy(() -> client.replyText("d-1", "네"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_SESSION_LOST);

        status = 409;
        responseBody = "{\"code\":\"DIALOGUE_ENDED\"}";
        assertThatThrownBy(() -> client.replyText("d-1", "네"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_SESSION_ENDED);

        status = 503;
        responseBody = "{\"code\":\"STT_UNAVAILABLE\"}";
        assertThatThrownBy(() -> client.replyText("d-1", "네"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_STT_UNAVAILABLE);
    }

    @Test
    @DisplayName("키 불일치(401)·FastAPI 422 detail·형식이 이상한 본문은 모두 503이다")
    void unknownFailuresAreUnavailable() {
        status = 401;
        responseBody = "{\"code\":\"AUTH_INVALID_KEY\"}";
        assertThatThrownBy(() -> client.finish("d-1"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_UNAVAILABLE);

        status = 422;
        responseBody = "{\"detail\":[{\"msg\":\"field required\"}]}";
        assertThatThrownBy(() -> client.finish("d-1"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_UNAVAILABLE);

        status = 200;
        responseBody = "{\"success\":true}";
        assertThatThrownBy(() -> client.finish("d-1"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_UNAVAILABLE);
    }

    @Test
    @DisplayName("호출 제한 시간이 지나면 연결을 끊고 504다")
    void timeout() {
        properties.setCallTimeout(Duration.ofMillis(300));
        delayMillis = 1500;
        responseBody = "{\"success\":true,\"data\":{}}";

        assertThatThrownBy(() -> client.finish("d-1"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_TIMEOUT);
    }

    @Test
    @DisplayName("대화 번호 형식이 이상하면 경로에 넣지 않는다")
    void rejectsOddDialogueId() {
        assertThatThrownBy(() -> client.finish("../wards/x"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_UNAVAILABLE);
        assertThat(lastPath.get()).isNull();
    }

    @Test
    @DisplayName("주소·키가 없으면 호출하지 않고 꺼짐(503)이다")
    void notConfigured() {
        properties.setApiKey("");
        assertThatThrownBy(() -> client.finish("d-1"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_DISABLED);
        assertThat(lastPath.get()).isNull();
    }

    @Test
    @DisplayName("음성 대답은 audio 1개 + frame 여러 장을 고정 파일 이름으로 보낸다")
    void multipart() {
        responseBody = "{\"success\":true,\"data\":{\"turnNo\":1}}";
        byte[] audio = "RIFF0000WAVEdata".getBytes(StandardCharsets.US_ASCII);
        byte[] frame = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1};

        client.replyAudio("d-1", audio, CompanionMedia.WAV, List.of(frame, frame),
                List.of(CompanionMedia.JPEG, CompanionMedia.JPEG));

        String sent = new String(lastBody.get(), StandardCharsets.ISO_8859_1);
        assertThat(lastPath.get()).isEqualTo("POST /companion/dialogues/d-1/reply/audio");
        assertThat(sent).contains("name=\"audio\"; filename=\"audio.wav\"");
        assertThat(sent.split("name=\"frame\"; filename=\"frame.jpg\"", -1)).hasSize(3);
        assertThat(sent).endsWith("--\r\n");
    }

    @Test
    @DisplayName("탈퇴 삭제는 DELETE /companion/wards/{wardId}/data, 실패는 예외로 알린다")
    void deleteWardData() {
        responseBody = "{\"success\":true,\"data\":{}}";
        client.deleteWardData("CW0001");
        assertThat(lastPath.get()).isEqualTo("DELETE /companion/wards/CW0001/data");

        status = 500;
        responseBody = "{}";
        assertThatThrownBy(() -> client.deleteWardData("CW0001")).isInstanceOf(CustomException.class);
    }
}

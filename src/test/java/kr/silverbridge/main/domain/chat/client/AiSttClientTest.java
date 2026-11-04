package kr.silverbridge.main.domain.chat.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kr.silverbridge.main.domain.chat.config.ChatSttProperties;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 챗봇 음성 인식 클라이언트 - 실제 HTTP(로컬 임시 서버)로 키 헤더·업로드 형태·오류 매핑·시간 제한·무로그를 확인한다. */
@ExtendWith(OutputCaptureExtension.class)
class AiSttClientTest {

    private static final String KEY = "stt-test-key";
    private static final String SECRET_TEXT = "비밀스러운받아쓰기";

    private HttpServer server;
    private final AtomicReference<String> receivedKey = new AtomicReference<>();
    private final AtomicReference<String> receivedContentType = new AtomicReference<>();
    private final AtomicReference<byte[]> receivedBody = new AtomicReference<>();
    private final AtomicBoolean redirectFollowed = new AtomicBoolean();
    private final AtomicReference<Responder> responder = new AtomicReference<>();
    private ChatSttProperties properties;
    private AiSttClient client;

    @FunctionalInterface
    interface Responder {
        void respond(HttpExchange ex) throws IOException;
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/companion/stt", ex -> {
            receivedKey.set(ex.getRequestHeaders().getFirst("X-API-Key"));
            receivedContentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            receivedBody.set(ex.getRequestBody().readAllBytes());
            responder.get().respond(ex);
        });
        server.createContext("/elsewhere", ex -> {
            redirectFollowed.set(true);
            json(ex, 200, "{\"data\":{\"text\":\"redirected\"}}");
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        properties = new ChatSttProperties();
        properties.setAiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey(KEY);
        client = new AiSttClient(properties, new ObjectMapper());
        responder.set(ex -> json(ex, 200, "{\"success\":true,\"data\":{\"text\":\"" + SECRET_TEXT + "\"}}"));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        client.shutdown();
    }

    @Test
    @DisplayName("성공: 키 헤더를 붙이고 audio 파트(고정 파일 이름)로 원본 바이트를 보내며 data.text만 돌려준다")
    void success(CapturedOutput output) {
        byte[] audio = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 7, 8, 9};

        String text = client.transcribe(audio);

        assertThat(text).isEqualTo(SECRET_TEXT);
        assertThat(receivedKey.get()).isEqualTo(KEY);
        assertThat(receivedContentType.get()).startsWith("multipart/form-data; boundary=");
        String body = new String(receivedBody.get(), StandardCharsets.ISO_8859_1);
        assertThat(body).contains("name=\"audio\"; filename=\"audio\"");
        assertThat(body).contains(new String(audio, StandardCharsets.ISO_8859_1));
        assertThat(output.getAll()).doesNotContain(SECRET_TEXT).doesNotContain(KEY);
    }

    @Test
    @DisplayName("못 알아들으면 빈 글자 그대로")
    void emptyText() {
        responder.set(ex -> json(ex, 200, "{\"success\":true,\"data\":{\"text\":\"\"}}"));
        assertThat(client.transcribe(new byte[]{1})).isEmpty();
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
            "413, AUDIO_TOO_LARGE, CHAT_STT_AUDIO_TOO_LARGE",
            "413, AUDIO_TOO_LONG, CHAT_STT_AUDIO_TOO_LARGE",
            "422, AUDIO_MISSING, CHAT_STT_AUDIO_INVALID",
            "422, AUDIO_FORMAT_UNSUPPORTED, CHAT_STT_AUDIO_INVALID",
            "422, AUDIO_UNREADABLE, CHAT_STT_AUDIO_INVALID",
            "503, STT_BUSY, CHAT_STT_BUSY",
            "503, STT_UNAVAILABLE, CHAT_STT_UNAVAILABLE",
            "401, AUTH_INVALID_KEY, CHAT_STT_UNAVAILABLE",
            "500, WHATEVER, CHAT_STT_UNAVAILABLE"
    })
    @DisplayName("AI 오류 코드 매핑 - 문구는 읽지 않는다")
    void errorMapping(int status, String aiCode, ErrorCode expected, CapturedOutput output) {
        responder.set(ex -> json(ex, status,
                "{\"success\":false,\"message\":\"" + SECRET_TEXT + "\",\"code\":\"" + aiCode + "\"}"));
        assertThatThrownBy(() -> client.transcribe(new byte[]{1}))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(expected);
        assertThat(output.getAll()).doesNotContain(SECRET_TEXT).doesNotContain("WHATEVER");
    }

    @Test
    @DisplayName("경로 없음(옛 AI 서버 404, 본문 없음) → 503 UNAVAILABLE")
    void notFound() {
        responder.set(ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        assertThatThrownBy(() -> client.transcribe(new byte[]{1}))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CHAT_STT_UNAVAILABLE);
    }

    @Test
    @DisplayName("data.text가 없으면 503")
    void missingText() {
        responder.set(ex -> json(ex, 200, "{\"success\":true,\"data\":{}}"));
        assertThatThrownBy(() -> client.transcribe(new byte[]{1}))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CHAT_STT_UNAVAILABLE);
    }

    @Test
    @DisplayName("리다이렉트는 따라가지 않는다(키 유출 방지)")
    void noRedirect() {
        responder.set(ex -> {
            ex.getResponseHeaders().set("Location", "/elsewhere");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        assertThatThrownBy(() -> client.transcribe(new byte[]{1})).isInstanceOf(CustomException.class);
        assertThat(redirectFollowed).isFalse();
    }

    @Test
    @DisplayName("호출 전체 시간 제한을 넘으면 연결을 끊고 503")
    void timeout() {
        properties.setCallTimeout(Duration.ofMillis(400));
        responder.set(ex -> {
            sleepQuietly(1_500);
            json(ex, 200, "{\"data\":{\"text\":\"late\"}}");
        });
        long started = System.nanoTime();
        assertThatThrownBy(() -> client.transcribe(new byte[]{1}))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CHAT_STT_UNAVAILABLE);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_400));
    }

    @Test
    @DisplayName("주소·키가 비면 503 DISABLED, AI를 부르지 않는다")
    void notConfigured() {
        properties.setApiKey("");
        assertThatThrownBy(() -> client.transcribe(new byte[]{1}))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CHAT_STT_DISABLED);
        assertThat(receivedBody.get()).isNull();
    }

    @Test
    @DisplayName("키에 줄바꿈(CRLF .env)이 있으면 보내지 않고 503, 로그에 키를 남기지 않는다")
    void keyWithControlChar(CapturedOutput output) {
        properties.setApiKey(KEY + "\r");
        assertThatThrownBy(() -> client.transcribe(new byte[]{1}))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CHAT_STT_UNAVAILABLE);
        assertThat(receivedBody.get()).isNull();
        assertThat(output.getAll()).doesNotContain(KEY);
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

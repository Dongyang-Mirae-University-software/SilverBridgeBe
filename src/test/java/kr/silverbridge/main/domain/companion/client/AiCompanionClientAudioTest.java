package kr.silverbridge.main.domain.companion.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 말벗 목소리 받기(2026-11-13) - 실제 HTTP(JDK HttpServer)로 경로·키·상태 매핑·형식·크기·시간 제한·리다이렉트를 본다. */
class AiCompanionClientAudioTest {

    private static final byte[] MP3 = {(byte) 0xFF, (byte) 0xFB, 0x10, 0x00, 0x01, 0x02};

    private HttpServer server;
    private AiCompanionClient client;
    private CompanionProperties properties;
    private final AtomicReference<String> lastKey = new AtomicReference<>();
    private final AtomicReference<String> lastUri = new AtomicReference<>();
    private final AtomicReference<String> lastWardHeader = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String contentType = "audio/mpeg";
    private volatile byte[] body = MP3;
    private volatile boolean chunked = false;
    private volatile long delayMillis = 0;
    private volatile String location = null;
    private ListAppender<ILoggingEvent> appender;
    private Logger clientLogger;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        properties = new CompanionProperties();
        properties.setEnabled(true);
        properties.setAiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("test-companion-key");
        properties.setAudioCallTimeout(Duration.ofSeconds(1));
        client = new AiCompanionClient(properties, new ObjectMapper());
        appender = new ListAppender<>();
        appender.start();
        clientLogger = (Logger) LoggerFactory.getLogger(AiCompanionClient.class);
        clientLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        clientLogger.detachAppender(appender);
        server.stop(0);
        client.shutdown();
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
        lastUri.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
        lastWardHeader.set(exchange.getRequestHeaders().getFirst(AiCompanionClient.WARD_ID_HEADER));
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (contentType != null) {
            exchange.getResponseHeaders().add("Content-Type", contentType);
        }
        if (location != null) {
            exchange.getResponseHeaders().add("Location", location);
        }
        exchange.sendResponseHeaders(status, chunked ? 0 : (body.length == 0 ? -1 : body.length));
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        } catch (IOException ignored) {
            // 클라이언트가 마감·크기 초과로 끊은 경우
        }
    }

    private static ErrorCode codeOf(Throwable e) {
        return ((CustomException) e).getErrorCode();
    }

    @Test
    @DisplayName("200: BE 대화 번호·소리 번호·wait=5로 부르고, 키는 헤더로, 소리 바이트와 형식을 그대로 돌려준다")
    void relaysAudio() {
        CompanionAudio audio = client.fetchUtteranceAudio("W00001", 42L, 3);

        assertThat(audio.body()).isEqualTo(MP3);
        assertThat(audio.contentType()).isEqualTo("audio/mpeg");
        assertThat(lastUri.get()).isEqualTo("GET /companion/sessions/42/utterances/3/audio?wait=5");
        assertThat(lastKey.get()).isEqualTo("test-companion-key");
        assertThat(lastWardHeader.get()).as("대화 주인 ID는 헤더로(점검 L-2) - 경로·쿼리에 싣지 않는다").isEqualTo("W00001");
        assertThat(lastUri.get()).doesNotContain("W00001");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @ValueSource(strings = {"W0\nX", "toolongwardid12345", "a b"})
    @DisplayName("대화 주인 ID 형식이 이상하면 AI를 부르지 않는다(헤더 삽입 방지)")
    void rejectsBadWardId(String wardId) {
        assertThatThrownBy(() -> client.fetchUtteranceAudio(wardId, 1L, 0))
                .extracting(AiCompanionClientAudioTest::codeOf).isEqualTo(ErrorCode.COMPANION_AUDIO_INDEX_INVALID);
        assertThat(lastUri.get()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"audio/mpeg", "audio/wav", "audio/ogg", "AUDIO/OGG; codecs=opus"})
    @DisplayName("허용 형식(mpeg·wav·ogg)은 매개변수를 떼고 소문자로 넘긴다")
    void allowedTypes(String type) {
        contentType = type;

        assertThat(client.fetchUtteranceAudio("W00001", 1L, 0).contentType()).isIn("audio/mpeg", "audio/wav", "audio/ogg");
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/json", "audio/webm", "audio/x-wav", "audio/mpegx"})
    @DisplayName("허용 밖 형식은 502 COMPANION_AUDIO_BAD_UPSTREAM")
    void rejectsOtherTypes(String type) {
        contentType = type;

        assertThatThrownBy(() -> client.fetchUtteranceAudio("W00001", 1L, 0))
                .extracting(AiCompanionClientAudioTest::codeOf).isEqualTo(ErrorCode.COMPANION_AUDIO_BAD_UPSTREAM);
    }

    @Test
    @DisplayName("Content-Type이 없으면 502")
    void missingType() {
        contentType = null;

        assertThatThrownBy(() -> client.fetchUtteranceAudio("W00001", 1L, 0))
                .extracting(AiCompanionClientAudioTest::codeOf).isEqualTo(ErrorCode.COMPANION_AUDIO_BAD_UPSTREAM);
    }

    @Test
    @DisplayName("1MB 초과(Content-Length 표기)는 502")
    void tooLargeDeclared() {
        body = new byte[properties.getAudioMaxBytes() + 1];

        assertThatThrownBy(() -> client.fetchUtteranceAudio("W00001", 1L, 0))
                .extracting(AiCompanionClientAudioTest::codeOf).isEqualTo(ErrorCode.COMPANION_AUDIO_BAD_UPSTREAM);
    }

    @Test
    @DisplayName("1MB 초과(길이 미표기 chunked)도 읽는 중에 끊고 502")
    void tooLargeChunked() {
        body = new byte[properties.getAudioMaxBytes() + 1];
        chunked = true;

        assertThatThrownBy(() -> client.fetchUtteranceAudio("W00001", 1L, 0))
                .extracting(AiCompanionClientAudioTest::codeOf).isEqualTo(ErrorCode.COMPANION_AUDIO_BAD_UPSTREAM);
    }

    @Test
    @DisplayName("정확히 1MB는 받는다")
    void exactlyMax() {
        body = new byte[properties.getAudioMaxBytes()];

        assertThat(client.fetchUtteranceAudio("W00001", 1L, 0).body()).hasSize(properties.getAudioMaxBytes());
    }

    @Test
    @DisplayName("빈 본문은 502")
    void emptyBody() {
        body = new byte[0];

        assertThatThrownBy(() -> client.fetchUtteranceAudio("W00001", 1L, 0))
                .extracting(AiCompanionClientAudioTest::codeOf).isEqualTo(ErrorCode.COMPANION_AUDIO_BAD_UPSTREAM);
    }

    @Test
    @DisplayName("AI 404(TTS_NOT_FOUND)는 404 COMPANION_AUDIO_NOT_FOUND")
    void notFound() {
        status = 404;
        contentType = "application/json";
        body = "{\"code\":\"TTS_NOT_FOUND\",\"message\":\"비밀 문장\"}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> client.fetchUtteranceAudio("W00001", 1L, 0))
                .extracting(AiCompanionClientAudioTest::codeOf).isEqualTo(ErrorCode.COMPANION_AUDIO_NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(ints = {503, 500, 401, 422, 400})
    @DisplayName("AI 503(TTS_NOT_READY)·그 밖의 실패는 503 COMPANION_AUDIO_NOT_READY")
    void notReady(int code) {
        status = code;
        contentType = "application/json";
        body = "{\"code\":\"TTS_NOT_READY\"}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> client.fetchUtteranceAudio("W00001", 1L, 0))
                .extracting(AiCompanionClientAudioTest::codeOf).isEqualTo(ErrorCode.COMPANION_AUDIO_NOT_READY);
    }

    @Test
    @DisplayName("리다이렉트는 따라가지 않는다(키 유출 방지) - 302는 503")
    void noRedirect() {
        status = 302;
        location = "http://127.0.0.1:1/elsewhere";
        body = new byte[0];

        assertThatThrownBy(() -> client.fetchUtteranceAudio("W00001", 1L, 0))
                .extracting(AiCompanionClientAudioTest::codeOf).isEqualTo(ErrorCode.COMPANION_AUDIO_NOT_READY);
        assertThat(lastUri.get()).startsWith("GET /companion/sessions/1/");
    }

    @Test
    @DisplayName("시간 초과(제한 1초, AI 2초 지연)는 503이고 제한 안에 끊는다")
    void timeout() {
        delayMillis = 2000;
        long started = System.nanoTime();

        assertThatThrownBy(() -> client.fetchUtteranceAudio("W00001", 1L, 0))
                .extracting(AiCompanionClientAudioTest::codeOf).isEqualTo(ErrorCode.COMPANION_AUDIO_NOT_READY);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1900));
    }

    @Test
    @DisplayName("연결 실패는 503")
    void connectionRefused() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        properties.setAiBaseUrl("http://127.0.0.1:" + closedPort);

        assertThatThrownBy(() -> client.fetchUtteranceAudio("W00001", 1L, 0))
                .extracting(AiCompanionClientAudioTest::codeOf).isEqualTo(ErrorCode.COMPANION_AUDIO_NOT_READY);
    }

    @Test
    @DisplayName("주소·키가 비면 503 COMPANION_DISABLED, AI를 부르지 않는다")
    void notConfigured() {
        properties.setApiKey("");

        assertThatThrownBy(() -> client.fetchUtteranceAudio("W00001", 1L, 0))
                .extracting(AiCompanionClientAudioTest::codeOf).isEqualTo(ErrorCode.COMPANION_DISABLED);
        assertThat(lastUri.get()).isNull();
    }

    @Test
    @DisplayName("로그에는 키·AI 오류 문구·주소가 없다")
    void logsHaveNoSecrets() {
        status = 404;
        contentType = "application/json";
        body = "{\"code\":\"TTS_NOT_FOUND\",\"message\":\"비밀 문장\"}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> client.fetchUtteranceAudio("W00001", 1L, 0)).isInstanceOf(CustomException.class);
        status = 503;
        assertThatThrownBy(() -> client.fetchUtteranceAudio("W00001", 1L, 0)).isInstanceOf(CustomException.class);

        assertThat(appender.list).isNotEmpty().allSatisfy(e -> assertThat(e.getFormattedMessage())
                .doesNotContain("test-companion-key").doesNotContain("비밀 문장").doesNotContain("127.0.0.1"));
    }
}

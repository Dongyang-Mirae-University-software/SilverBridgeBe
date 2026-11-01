package kr.silverbridge.main.domain.checkin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kr.silverbridge.main.domain.checkin.client.CheckinAiClient.AudioFormat;
import kr.silverbridge.main.domain.checkin.config.CheckinProperties;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckinAiClientTest {

    private static final byte[] WAV = "RIFF\0\0\0\0WAVEfmt fake".getBytes(StandardCharsets.ISO_8859_1);

    private HttpServer server;
    private CheckinAiClient client;
    private final CheckinProperties properties = new CheckinProperties();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastKey = new AtomicReference<>();
    private final AtomicReference<String> lastUri = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String response = "{\"success\":true,\"data\":{\"turnNo\":1,\"reply\":\"네\"}}";
    private volatile long delayMs = 0;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        properties.setAiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("test-key");
        properties.setTurnTimeout(Duration.ofMillis(500));
        client = new CheckinAiClient(properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        client.shutdown();
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
        lastUri.set(exchange.getRequestURI().toString());
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (status == 302) {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/steal");
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    @DisplayName("턴: 키는 헤더로만, multipart에 userId·turnNo·음성을 싣고 사진이 없으면 사진 파트가 없다")
    void turnWithoutFrames() {
        var data = client.turn("ck_abcdefghijklmnopqrstuvwx", "wA1b2C", 3, AudioFormat.WAV, WAV, List.of());

        assertThat(data.path("reply").asText()).isEqualTo("네");
        assertThat(lastKey.get()).isEqualTo("test-key");
        assertThat(lastUri.get()).isEqualTo("/api/v1/checkin/sessions/ck_abcdefghijklmnopqrstuvwx/turns");
        assertThat(lastBody.get())
                .contains("name=\"userId\"\r\n\r\nwA1b2C")
                .contains("name=\"turnNo\"\r\n\r\n3")
                .contains("name=\"audio\"; filename=\"audio.wav\"")
                .doesNotContain("name=\"frames\"")
                .doesNotContain("test-key");
    }

    @Test
    @DisplayName("턴: 사진은 frames 파트로 장마다 하나씩, 고정 파일 이름으로 보낸다")
    void turnWithFrames() {
        client.turn("ck_abcdefghijklmnopqrstuvwx", "wA1b2C", 1, AudioFormat.WAV, WAV,
                List.of(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}));

        assertThat(lastBody.get().split("name=\"frames\"", -1)).hasSize(3);
        assertThat(lastBody.get()).contains("filename=\"frame1.jpg\"").contains("filename=\"frame2.jpg\"");
    }

    @Test
    @DisplayName("AI 오류 코드로 안내를 나눈다: 못 알아들음 422 / 너무 김 413 / 바쁨 429")
    void errorCodes() {
        assertTurnFails(422, "SPEECH_NOT_RECOGNIZED", ErrorCode.CHECKIN_SPEECH_NOT_RECOGNIZED);
        assertTurnFails(422, "AUDIO_UNREADABLE", ErrorCode.CHECKIN_INVALID_AUDIO);
        assertTurnFails(413, "AUDIO_TOO_LONG", ErrorCode.CHECKIN_AUDIO_TOO_LONG);
        assertTurnFails(413, "AUDIO_TOO_LARGE", ErrorCode.CHECKIN_AUDIO_TOO_LARGE);
        assertTurnFails(429, "CHECKIN_BUSY", ErrorCode.CHECKIN_BUSY);
    }

    @Test
    @DisplayName("키 불일치·BE와 어긋난 순서·모르는 코드는 503으로 묶는다")
    void otherFailures() {
        assertTurnFails(401, "AUTH_INVALID_KEY", ErrorCode.CHECKIN_UNAVAILABLE);
        assertTurnFails(409, "TURN_OUT_OF_ORDER", ErrorCode.CHECKIN_UNAVAILABLE);
        assertTurnFails(500, "SOMETHING_NEW", ErrorCode.CHECKIN_UNAVAILABLE);
    }

    @Test
    @DisplayName("리다이렉트는 따라가지 않는다(키 헤더 유출 방지)")
    void noRedirect() {
        status = 302;
        response = "";
        assertThatThrownBy(() -> client.turn("ck_abcdefghijklmnopqrstuvwx", "u1", 1, AudioFormat.WAV, WAV, List.of()))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CHECKIN_UNAVAILABLE);
    }

    @Test
    @DisplayName("호출 전체 마감을 넘기면 504 CHECKIN_TIMEOUT")
    void timeout() {
        delayMs = 1500;
        assertThatThrownBy(() -> client.turn("ck_abcdefghijklmnopqrstuvwx", "u1", 1, AudioFormat.WAV, WAV, List.of()))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CHECKIN_TIMEOUT);
    }

    @Test
    @DisplayName("탈퇴 삭제: userId를 쿼리로 보내고 200만 성공, 404(옛 AI)는 실패다")
    void deleteLogs() {
        response = "{\"success\":true,\"data\":{\"sessions\":1,\"turns\":2}}";
        client.deleteLogs("wA1b2C");
        assertThat(lastUri.get()).isEqualTo("/api/v1/checkin/logs?userId=wA1b2C");

        status = 404;
        assertThatThrownBy(() -> client.deleteLogs("wA1b2C")).isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("음성 형식은 매직 바이트로만 판별한다")
    void detect() {
        assertThat(AudioFormat.detect(WAV)).isEqualTo(AudioFormat.WAV);
        assertThat(AudioFormat.detect(new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0, 0, 0, 0, 0}))
                .isEqualTo(AudioFormat.WEBM);
        assertThat(AudioFormat.detect("OggS00000000".getBytes())).isEqualTo(AudioFormat.OGG);
        assertThat(AudioFormat.detect("0000ftypM4A ".getBytes())).isEqualTo(AudioFormat.M4A);
        assertThat(AudioFormat.detect("<html>hello!".getBytes())).isNull();
        assertThat(AudioFormat.detect(new byte[3])).isNull();
    }

    private void assertTurnFails(int code, String errorCode, ErrorCode expected) {
        status = code;
        response = "{\"success\":false,\"message\":\"원문 메시지\",\"errorCode\":\"" + errorCode + "\",\"data\":null}";
        assertThatThrownBy(() -> client.turn("ck_abcdefghijklmnopqrstuvwx", "u1", 1, AudioFormat.WAV, WAV, List.of()))
                .as(errorCode)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(expected);
    }
}

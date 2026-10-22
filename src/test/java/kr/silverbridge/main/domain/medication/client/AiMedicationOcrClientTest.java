package kr.silverbridge.main.domain.medication.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kr.silverbridge.main.domain.medication.client.AiMedicationOcrClient.ImageFormat;
import kr.silverbridge.main.domain.medication.config.MedicationOcrProperties;
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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 약 봉투 OCR 클라이언트 - 실제 HTTP(로컬 임시 서버)로 키 헤더·multipart 형태·오류 매핑·시간 제한·리다이렉트 미추종을 확인한다. */
class AiMedicationOcrClientTest {

    private static final String KEY = "test-key";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};

    private HttpServer server;
    private final AtomicReference<Integer> nextStatus = new AtomicReference<>(200);
    private final AtomicReference<String> nextBody = new AtomicReference<>("{\"readable\":true,\"reason\":null,\"drugs\":[]}");
    private final AtomicReference<Long> delayMs = new AtomicReference<>(0L);
    private final AtomicReference<String> receivedKey = new AtomicReference<>();
    private final AtomicReference<String> receivedContentType = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicBoolean redirectFollowed = new AtomicBoolean();
    private MedicationOcrProperties properties;
    private AiMedicationOcrClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/medication/ocr", this::handle);
        server.createContext("/elsewhere", ex -> {
            redirectFollowed.set(true);
            json(ex, 200, "{\"readable\":true,\"drugs\":[]}");
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        properties = new MedicationOcrProperties();
        properties.setAiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey(KEY);
        client = new AiMedicationOcrClient(properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        client.shutdown();
    }

    private void handle(HttpExchange ex) throws IOException {
        receivedKey.set(ex.getRequestHeaders().getFirst("X-API-Key"));
        receivedContentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
        receivedBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
        if (delayMs.get() > 0) {
            try {
                Thread.sleep(delayMs.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (nextStatus.get() == 302) {
            ex.getResponseHeaders().set("Location", "/elsewhere");
            ex.sendResponseHeaders(302, -1);
            ex.close();
            return;
        }
        json(ex, nextStatus.get(), nextBody.get());
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private void respond(int status, String body) {
        nextStatus.set(status);
        nextBody.set(body);
    }

    private ErrorCode codeOf(Runnable call) {
        try {
            call.run();
        } catch (CustomException e) {
            return e.getErrorCode();
        }
        throw new AssertionError("CustomException 이 나야 한다");
    }

    @Test
    @DisplayName("200: 래퍼 없는 본문을 그대로 읽고, 키는 헤더로, 사진은 image 파트에 고정 파일 이름으로 보낸다")
    void success_sendsKeyHeaderAndMultipart() {
        respond(200, "{\"readable\":true,\"reason\":\"ok\",\"drugs\":[{\"name\":\"타이레놀정\"}]}");

        JsonNode result = client.read(JPEG, ImageFormat.JPEG);

        assertThat(result.path("readable").asBoolean()).isTrue();
        assertThat(result.path("drugs").get(0).path("name").asText()).isEqualTo("타이레놀정");
        assertThat(receivedKey.get()).isEqualTo(KEY);
        assertThat(receivedContentType.get()).startsWith("multipart/form-data; boundary=");
        assertThat(receivedBody.get())
                .contains("Content-Disposition: form-data; name=\"image\"; filename=\"image.jpg\"")
                .contains("Content-Type: image/jpeg")
                // 점검 L-5: 사진을 복사하지 않고 바로 써도 본문은 머리 + 사진 + 꼬리 그대로다
                .contains(new String(JPEG, StandardCharsets.ISO_8859_1) + "\r\n--")
                .endsWith("--\r\n");
    }

    @Test
    @DisplayName("AI 오류: 401·500 → 503, 413 → 413, 400·422 → 400 - 오류 본문 문구는 내리지 않는다")
    void errorMapping() {
        respond(401, "{\"success\":false,\"errorCode\":\"UNAUTHORIZED\"}");
        assertThat(codeOf(() -> client.read(JPEG, ImageFormat.JPEG))).isEqualTo(ErrorCode.MEDICATION_OCR_UNAVAILABLE);
        respond(500, "{\"success\":false,\"message\":\"내부 오류 원문\"}");
        assertThat(codeOf(() -> client.read(JPEG, ImageFormat.JPEG))).isEqualTo(ErrorCode.MEDICATION_OCR_UNAVAILABLE);
        respond(503, "{}");
        assertThat(codeOf(() -> client.read(JPEG, ImageFormat.JPEG))).isEqualTo(ErrorCode.MEDICATION_OCR_UNAVAILABLE);
        respond(413, "{}");
        assertThat(codeOf(() -> client.read(JPEG, ImageFormat.JPEG))).isEqualTo(ErrorCode.MEDICATION_OCR_IMAGE_TOO_LARGE);
        respond(422, "{}");
        assertThat(codeOf(() -> client.read(JPEG, ImageFormat.JPEG))).isEqualTo(ErrorCode.MEDICATION_OCR_INVALID_IMAGE);
        respond(400, "{}");
        assertThat(codeOf(() -> client.read(JPEG, ImageFormat.JPEG))).isEqualTo(ErrorCode.MEDICATION_OCR_INVALID_IMAGE);
    }

    @Test
    @DisplayName("호출 전체 시간 제한을 넘기면 504")
    void timeout() {
        properties.setCallTimeout(Duration.ofMillis(300));
        delayMs.set(1_500L);

        assertThat(codeOf(() -> client.read(JPEG, ImageFormat.JPEG))).isEqualTo(ErrorCode.MEDICATION_OCR_TIMEOUT);
    }

    @Test
    @DisplayName("리다이렉트는 따라가지 않는다(키 헤더 유출 방지) - 503")
    void redirectNotFollowed() {
        respond(302, "");

        assertThat(codeOf(() -> client.read(JPEG, ImageFormat.JPEG))).isEqualTo(ErrorCode.MEDICATION_OCR_UNAVAILABLE);
        assertThat(redirectFollowed).isFalse();
    }

    @Test
    @DisplayName("응답이 객체가 아니거나 JSON이 아니거나 크기 상한을 넘으면 503")
    void malformedReply() {
        respond(200, "[1,2]");
        assertThat(codeOf(() -> client.read(JPEG, ImageFormat.JPEG))).isEqualTo(ErrorCode.MEDICATION_OCR_UNAVAILABLE);
        respond(200, "not-json");
        assertThat(codeOf(() -> client.read(JPEG, ImageFormat.JPEG))).isEqualTo(ErrorCode.MEDICATION_OCR_UNAVAILABLE);
        properties.setMaxReplyBytes(64);
        respond(200, "{\"readable\":true,\"drugs\":[{\"name\":\"" + "x".repeat(200) + "\"}]}");
        assertThat(codeOf(() -> client.read(JPEG, ImageFormat.JPEG))).isEqualTo(ErrorCode.MEDICATION_OCR_UNAVAILABLE);
    }

    @Test
    @DisplayName("AI_API_KEY 가 없으면 호출하지 않고 503")
    void missingKey() {
        properties.setApiKey("");

        assertThat(codeOf(() -> client.read(JPEG, ImageFormat.JPEG))).isEqualTo(ErrorCode.MEDICATION_OCR_UNAVAILABLE);
        assertThat(receivedKey.get()).isNull();
    }

    @Test
    @DisplayName("형식 판별은 매직 바이트로만 - jpeg·png·webp 외(gif·텍스트·빈 값)는 null")
    void detectFormat() {
        assertThat(ImageFormat.detect(JPEG)).isEqualTo(ImageFormat.JPEG);
        assertThat(ImageFormat.detect(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})).isEqualTo(ImageFormat.PNG);
        assertThat(ImageFormat.detect("RIFF0000WEBPVP8 ".getBytes(StandardCharsets.US_ASCII))).isEqualTo(ImageFormat.WEBP);
        assertThat(ImageFormat.detect("GIF89a....".getBytes(StandardCharsets.US_ASCII))).isNull();
        assertThat(ImageFormat.detect("hello".getBytes(StandardCharsets.US_ASCII))).isNull();
        assertThat(ImageFormat.detect(new byte[0])).isNull();
        assertThat(ImageFormat.detect(null)).isNull();
    }
}

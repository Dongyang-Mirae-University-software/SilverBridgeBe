package kr.silverbridge.main.domain.anomaly.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.camera.config.CameraStreamProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 서버 클립 API 클라이언트 - {@code POST /api/v1/live-streams/{sessionId}/clips}(계약서 2026-10-04 최종본).
 *
 * <p>접속 정보(주소·키·연결 제한)는 영상 중계와 같은 {@link CameraStreamProperties}를 쓴다 - AI 서버 하나에 설정이 둘로
 * 갈리지 않게 한다. 응답 제한만 클립 전용({@code anomaly.clip.request-timeout})이다: AI가 뒤 구간을 기다린 뒤 인코딩하므로
 * 일반 5초로는 부족하다.</p>
 *
 * <p>AI 키는 {@code X-API-Key} 헤더로만 보내고 로그·예외에 남기지 않는다. 리다이렉트는 따라가지 않는다(다른 호스트로
 * 키 헤더가 새는 것 방지 - 영상 중계 L-2와 같은 기준). 로그에는 sessionId·결과 코드·HTTP 상태까지만 남긴다.</p>
 *
 * <p>예외를 던지지 않고 {@link ClipResult}로 돌려준다 - 호출부가 결과마다 쿨다운 해제 여부를 정한다.</p>
 */
@Slf4j
@Component
public class AiClipClient {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String CLIPS_PATH = "/api/v1/live-streams/{sessionId}/clips";
    /** WebM(Matroska) 파일은 EBML 헤더 {@code 1A 45 DF A3}로 시작한다. */
    private static final byte[] EBML_MAGIC = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};

    private final CameraStreamProperties streamProperties;
    private final AnomalyProperties anomalyProperties;
    private final ObjectMapper objectMapper;
    private final SimpleClientHttpRequestFactory requestFactory;

    public AiClipClient(CameraStreamProperties streamProperties, AnomalyProperties anomalyProperties,
                        ObjectMapper objectMapper) {
        this.streamProperties = streamProperties;
        this.anomalyProperties = anomalyProperties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout((int) streamProperties.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) anomalyProperties.getClip().getRequestTimeout().toMillis());
        this.requestFactory = factory;
    }

    /**
     * 클립 요청 결과.
     *
     * <p>{@code retryable}이면 호출부가 클립 쿨다운을 풀어 다음 감지에서 다시 시도한다. 401·422처럼 설정·코드 결함이라
     * 다시 보내도 같은 결과인 것만 {@code false}다 - 그런 결과로 AI를 1분마다 두드리지 않는다.</p>
     */
    public enum Outcome {
        OK(false),
        /** 409 CLIP_NOT_ENOUGH_FRAMES - 구간에 프레임이 없다(오래된 감지·버퍼 소실). */
        NOT_ENOUGH_FRAMES(true),
        /** 404 STREAM_SESSION_NOT_FOUND - AI가 세션을 모른다(송출 종료). */
        SESSION_NOT_FOUND(true),
        /** 429 CLIP_BUSY - AI 인코딩 상한 초과. */
        BUSY(true),
        /** 500 CLIP_ENCODE_FAILED 또는 그 밖의 5xx. */
        SERVER_ERROR(true),
        /** 401·422 등 다시 보내도 같은 4xx(키·파라미터 결함). */
        REJECTED(false),
        /** 200이지만 WebM이 아니거나 비어 있음. */
        INVALID_RESPONSE(true),
        /** 크기 상한 초과. */
        TOO_LARGE(true),
        /** 연결·응답 시간 초과, 네트워크 오류, 리다이렉트. */
        UNAVAILABLE(true),
        /** AI 키 미설정 - 설정이 바뀔 때까지 같은 결과다. */
        NOT_CONFIGURED(false);

        private final boolean retryable;

        Outcome(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean isRetryable() {
            return retryable;
        }
    }

    /** AI 응답 헤더의 클립 정보. 헤더가 없거나 형식이 틀리면 해당 값은 null(0으로 채우지 않는다). */
    public record ClipMeta(Integer durationMs, Integer frames, Integer width, Integer height,
                           OffsetDateTime startedAt) {}

    /** 결과. {@code OK}일 때만 {@code body}·{@code meta}가 있다. */
    public record ClipResult(Outcome outcome, byte[] body, ClipMeta meta, String errorCode, Integer httpStatus) {

        static ClipResult ok(byte[] body, ClipMeta meta) {
            return new ClipResult(Outcome.OK, body, meta, null, 200);
        }

        static ClipResult fail(Outcome outcome, String errorCode, Integer httpStatus) {
            return new ClipResult(outcome, null, null, errorCode, httpStatus);
        }

        public boolean isOk() {
            return outcome == Outcome.OK;
        }
    }

    /**
     * 클립을 요청한다.
     *
     * @param detectedAt 감지 시각. null이면 보내지 않는다(계약상 AI가 요청 수신 시각을 쓴다)
     */
    public ClipResult requestClip(String sessionId, OffsetDateTime detectedAt) {
        if (!StringUtils.hasText(streamProperties.getApiKey())) {
            log.warn("[ANOMALY-CLIP] AI_API_KEY 미설정 - 클립 요청 불가: sessionId={}", sessionId);
            return ClipResult.fail(Outcome.NOT_CONFIGURED, null, null);
        }
        AnomalyProperties.Clip clip = anomalyProperties.getClip();
        try {
            ClientHttpRequest request = requestFactory.createRequest(uri(sessionId), HttpMethod.POST);
            request.getHeaders().set(API_KEY_HEADER, streamProperties.getApiKey());
            request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] payload = objectMapper.writeValueAsBytes(requestBody(detectedAt, clip));
            try (OutputStream body = request.getBody()) {
                body.write(payload);
            }
            try (ClientHttpResponse response = request.execute()) {
                return readResponse(sessionId, response, clip.getMaxBytes());
            }
        } catch (IOException e) {
            // 예외 원문에는 접속 주소가 섞일 수 있어 클래스명만 남긴다
            log.warn("[ANOMALY-CLIP] AI 호출 실패: sessionId={}, error={}", sessionId, e.getClass().getSimpleName());
            return ClipResult.fail(Outcome.UNAVAILABLE, null, null);
        }
    }

    private Map<String, Object> requestBody(OffsetDateTime detectedAt, AnomalyProperties.Clip clip) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (detectedAt != null) {
            body.put("detectedAt", DateTimeFormatter.ISO_INSTANT.format(detectedAt.toInstant()));
        }
        body.put("preSeconds", clip.getPreSeconds());
        body.put("postSeconds", clip.getPostSeconds());
        return body;
    }

    private ClipResult readResponse(String sessionId, ClientHttpResponse response, int maxBytes) throws IOException {
        int status = response.getStatusCode().value();
        if (status != 200) {
            String errorCode = readErrorCode(response);
            Outcome outcome = outcomeOf(status, errorCode);
            log.warn("[ANOMALY-CLIP] AI 응답 실패: sessionId={}, status={}, errorCode={}, outcome={}",
                    sessionId, status, errorCode, outcome);
            return ClipResult.fail(outcome, errorCode, status);
        }

        byte[] bytes;
        try (InputStream body = response.getBody()) {
            bytes = body.readNBytes(maxBytes + 1);
        }
        if (bytes.length > maxBytes) {
            log.warn("[ANOMALY-CLIP] 클립 크기 상한 초과 - 저장 안 함: sessionId={}, limit={}", sessionId, maxBytes);
            return ClipResult.fail(Outcome.TOO_LARGE, null, status);
        }
        if (!hasEbmlMagic(bytes)) {
            log.warn("[ANOMALY-CLIP] WebM 시그니처 불일치 - 저장 안 함: sessionId={}, size={}", sessionId, bytes.length);
            return ClipResult.fail(Outcome.INVALID_RESPONSE, null, status);
        }

        var headers = response.getHeaders();
        ClipMeta meta = new ClipMeta(
                intHeader(headers.getFirst("X-Clip-Duration-Ms")),
                intHeader(headers.getFirst("X-Clip-Frames")),
                intHeader(headers.getFirst("X-Clip-Width")),
                intHeader(headers.getFirst("X-Clip-Height")),
                timeHeader(headers.getFirst("X-Clip-Started-At")));
        return ClipResult.ok(bytes, meta);
    }

    static Outcome outcomeOf(int status, String errorCode) {
        if (status >= 300 && status < 400) {
            return Outcome.UNAVAILABLE;   // 리다이렉트는 따라가지 않고 장애로 본다
        }
        return switch (status) {
            case 409 -> Outcome.NOT_ENOUGH_FRAMES;
            case 404 -> Outcome.SESSION_NOT_FOUND;
            case 429 -> Outcome.BUSY;
            default -> status >= 500 ? Outcome.SERVER_ERROR : Outcome.REJECTED;
        };
    }

    /** 오류 본문 {@code {success, message, errorCode, data}}에서 errorCode만 꺼낸다. 형식이 다르면 null. */
    private String readErrorCode(ClientHttpResponse response) {
        try (InputStream body = response.getBody()) {
            byte[] bytes = body.readNBytes(4096);
            if (bytes.length == 0) {
                return null;
            }
            JsonNode node = objectMapper.readTree(bytes).path("errorCode");
            return node.isTextual() && node.asText().matches("[A-Z0-9_]{1,64}") ? node.asText() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static boolean hasEbmlMagic(byte[] bytes) {
        if (bytes == null || bytes.length < EBML_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < EBML_MAGIC.length; i++) {
            if (bytes[i] != EBML_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static Integer intHeader(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static OffsetDateTime timeHeader(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.trim());
        } catch (DateTimeParseException ignored) {
            // 오프셋 없는 형식 - AI 시각 관례(naive UTC)대로 UTC로 읽는다
        }
        try {
            return LocalDateTime.parse(raw.trim()).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private URI uri(String sessionId) {
        // encode()를 치환 전에 두어 변수 값의 '/'까지 인코딩한다(세션 ID로 다른 AI 경로를 가리킬 수 없게)
        return UriComponentsBuilder.fromUriString(streamProperties.getAiBaseUrl())
                .path(CLIPS_PATH)
                .encode()
                .buildAndExpand(sessionId)
                .toUri();
    }
}

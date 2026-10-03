package kr.silverbridge.main.domain.camera.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.camera.config.CameraStreamProperties;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * AI 서버 라이브 스트림 REST 클라이언트(보호자 영상 중계용).
 *
 * <p>AI 키는 {@code X-API-Key} 헤더로만 보낸다 - AI REST는 쿼리 키를 받지 않고, 쿼리에 실으면 접속 로그에 남는다.
 * 로그에는 세션 ID·응답 코드까지만 남기고 키·응답 본문은 남기지 않는다.</p>
 *
 * <p>응답 제한이 둘이다: 목록·상태·스냅샷은 일반 제한({@code readTimeout}), 끝이 없는 MJPEG는 읽기 한 번의
 * 무수신 제한({@code streamIdleTimeout})만 둔다(외부 HTTP 타임아웃 필수 규칙의 예외 - 2026-10-03).</p>
 */
@Slf4j
@Component
public class AiStreamClient {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String LIVE_STREAMS_PATH = "/api/v1/live-streams";

    private final CameraStreamProperties properties;
    private final ObjectMapper objectMapper;
    private final SimpleClientHttpRequestFactory requestFactory;

    public AiStreamClient(CameraStreamProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getReadTimeout().toMillis());
        this.requestFactory = factory;
    }

    /** 송출 중인 세션 전체({@code sessionId} → 상태). AI가 목록에 넣지 않은 세션은 송출하지 않는 것이다. */
    public Map<String, AiLiveStream> fetchLiveStreams() {
        try (ClientHttpResponse response = execute(requestFactory, uri(LIVE_STREAMS_PATH))) {
            requireSuccess(response, "live-streams", null);
            JsonNode data = readData(response);
            Map<String, AiLiveStream> streams = new HashMap<>();
            if (data.isArray()) {
                for (JsonNode node : data) {
                    String sessionId = node.path("sessionId").asText(null);
                    if (StringUtils.hasText(sessionId)) {
                        streams.put(sessionId, new AiLiveStream(sessionId,
                                textOrNull(node, "status"), parseAiTime(textOrNull(node, "lastFrameAt"))));
                    }
                }
            }
            return streams;
        } catch (IOException e) {
            throw unavailable("live-streams", null, e);
        }
    }

    /** 세션 상태. AI가 세션을 모르면(송출 안 함) 빈 값. */
    public Optional<AiStreamStatus> fetchStatus(String sessionId) {
        try (ClientHttpResponse response = execute(requestFactory, uri(LIVE_STREAMS_PATH + "/{id}/status", sessionId))) {
            if (response.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return Optional.empty();
            }
            requireSuccess(response, "status", sessionId);
            JsonNode data = readData(response);
            if (!data.isObject()) {
                return Optional.empty();
            }
            return Optional.of(new AiStreamStatus(
                    textOrNull(data, "status"),
                    parseAiTime(textOrNull(data, "lastFrameAt")),
                    data.hasNonNull("fps") ? data.get("fps").asDouble() : null,
                    data.path("isAnalyzing").asBoolean(false)));
        } catch (IOException e) {
            throw unavailable("status", sessionId, e);
        }
    }

    /** 최신 프레임(JPEG). 아직 프레임이 없거나 세션이 없으면 빈 값. */
    public Optional<byte[]> fetchLatestFrame(String sessionId) {
        try (ClientHttpResponse response = execute(requestFactory,
                uri(LIVE_STREAMS_PATH + "/{id}/latest-frame", sessionId))) {
            if (response.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return Optional.empty();
            }
            requireSuccess(response, "latest-frame", sessionId);
            try (InputStream body = response.getBody()) {
                byte[] bytes = body.readNBytes(properties.getMaxFrameBytes() + 1);
                if (bytes.length > properties.getMaxFrameBytes()) {
                    throw new AiStreamUnavailableException("AI 스냅샷이 크기 상한을 넘음");
                }
                return Optional.of(bytes);
            }
        } catch (IOException e) {
            throw unavailable("latest-frame", sessionId, e);
        }
    }

    /**
     * MJPEG 스트림을 연다. 호출부가 반환값을 반드시 닫아야 한다(AI 연결이 남는다).
     *
     * <p>Spring {@code ClientHttpResponse}를 쓰지 않는다 - 그 {@code close()}는 연결 재사용을 위해 남은 본문을 끝까지
     * 읽는데(drain), AI MJPEG는 끝나지 않아 시청자가 떠나도 닫기가 영원히 멈춘다(자리·스레드 누수). 그래서
     * {@link HttpURLConnection}을 직접 쓰고 {@code disconnect()}로 소켓을 바로 끊는다.</p>
     *
     * @throws CustomException {@code CAMERA_NOT_STREAMING} - AI가 세션을 모른다(송출 안 함)
     */
    public AiMjpegStream openMjpeg(String sessionId) {
        requireApiKey();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) uri(LIVE_STREAMS_PATH + "/{id}/mjpeg", sessionId).toURL().openConnection();
            connection.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
            connection.setReadTimeout((int) properties.getStreamIdleTimeout().toMillis());   // 읽기 1회 무수신 제한
            connection.setUseCaches(false);
            connection.setRequestProperty(API_KEY_HEADER, properties.getApiKey());

            int status = connection.getResponseCode();
            if (status == HttpStatus.NOT_FOUND.value()) {
                connection.disconnect();
                throw new CustomException(ErrorCode.CAMERA_NOT_STREAMING);
            }
            if (status < 200 || status >= 300) {
                log.warn("[CAMERA-STREAM] AI 응답 실패: call=mjpeg, sessionId={}, status={}", sessionId, status);
                connection.disconnect();
                throw new AiStreamUnavailableException("AI 응답 실패: mjpeg " + status);
            }
            return new AiMjpegStream(connection.getContentType(), connection.getInputStream(), connection::disconnect);
        } catch (IOException e) {
            if (connection != null) {
                connection.disconnect();
            }
            throw unavailable("mjpeg", sessionId, e);
        }
    }

    private void requireApiKey() {
        if (!StringUtils.hasText(properties.getApiKey())) {
            log.warn("[CAMERA-STREAM] AI_API_KEY 미설정 - 영상 중계 불가(.env.dev에 AI_API_KEY 주입 필요)");
            throw new AiStreamUnavailableException("AI API Key 미설정");
        }
    }

    private ClientHttpResponse execute(SimpleClientHttpRequestFactory factory, URI uri) throws IOException {
        requireApiKey();
        ClientHttpRequest request = factory.createRequest(uri, HttpMethod.GET);
        request.getHeaders().set(API_KEY_HEADER, properties.getApiKey());
        return request.execute();
    }

    private URI uri(String path, Object... variables) {
        // encode()를 치환 전에 두어 변수 값의 '/'까지 인코딩한다(세션 ID로 다른 AI 경로를 가리킬 수 없게)
        return UriComponentsBuilder.fromUriString(properties.getAiBaseUrl())
                .path(path)
                .encode()
                .buildAndExpand(variables)
                .toUri();
    }

    private void requireSuccess(ClientHttpResponse response, String call, String sessionId) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (!status.is2xxSuccessful()) {
            log.warn("[CAMERA-STREAM] AI 응답 실패: call={}, sessionId={}, status={}", call, sessionId, status.value());
            throw new AiStreamUnavailableException("AI 응답 실패: " + call + " " + status.value());
        }
    }

    /** AI 공통 응답 {@code {success, message, data}}에서 data만 꺼낸다. */
    private JsonNode readData(ClientHttpResponse response) throws IOException {
        try (InputStream body = response.getBody()) {
            return objectMapper.readTree(body).path("data");
        }
    }

    private AiStreamUnavailableException unavailable(String call, String sessionId, IOException e) {
        // 예외 원문에는 접속 주소가 섞일 수 있어 클래스명만 남긴다
        log.warn("[CAMERA-STREAM] AI 호출 실패: call={}, sessionId={}, error={}",
                call, sessionId, e.getClass().getSimpleName());
        return new AiStreamUnavailableException("AI 호출 실패: " + call, e);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** AI 시각은 오프셋 없는 UTC(예 {@code 2026-05-30T10:20:22.939247})다. 오프셋이 붙어 오면 그대로 읽는다. */
    static OffsetDateTime parseAiTime(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException ignored) {
            // 오프셋 없는 형식 - 아래에서 UTC로 읽는다
        }
        try {
            return LocalDateTime.parse(raw).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 열린 MJPEG 스트림. 닫으면 소켓을 바로 끊는다(본문을 읽어 비우지 않는다) - 다른 스레드에서 닫아도
     * 읽고 있던 쪽이 예외로 빠져나온다(서버 종료 처리).
     */
    public record AiMjpegStream(String contentType, InputStream body, Runnable disconnect) implements Closeable {
        @Override
        public void close() {
            disconnect.run();
        }
    }

    /** AI 목록의 세션 1건. */
    public record AiLiveStream(String sessionId, String status, OffsetDateTime lastFrameAt) {}

    /** AI 세션 상태. {@code viewerCount}는 다른 시청자 수가 드러나서 옮기지 않는다. */
    public record AiStreamStatus(String status, OffsetDateTime lastFrameAt, Double fps, boolean isAnalyzing) {}
}

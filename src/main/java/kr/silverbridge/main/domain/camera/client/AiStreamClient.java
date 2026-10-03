package kr.silverbridge.main.domain.camera.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.camera.config.CameraStreamProperties;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 서버 라이브 스트림 REST 클라이언트(보호자 영상 중계용).
 *
 * <p>AI 키는 {@code X-API-Key} 헤더로만 보낸다 - AI REST는 쿼리 키를 받지 않고, 쿼리에 실으면 접속 로그에 남는다.
 * 로그에는 세션 ID·응답 코드까지만 남기고 키·응답 본문은 남기지 않는다.</p>
 *
 * <p>응답 제한이 둘이다: 목록·상태·스냅샷은 <b>호출 전체 제한</b>({@code callTimeout}, 마감 시 연결을 끊는다 - 2026-10-04
 * 점검 L-3), 끝이 없는 MJPEG는 읽기 한 번의 무수신 제한({@code streamIdleTimeout})만 둔다(외부 HTTP 타임아웃 필수 규칙의
 * 예외 - 2026-10-03).</p>
 */
@Slf4j
@Component
public class AiStreamClient {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String LIVE_STREAMS_PATH = "/api/v1/live-streams";
    /** 목록·상태 JSON 최대 크기 - 메모리에 올리므로 상한을 둔다. */
    private static final int MAX_JSON_BYTES = 1024 * 1024;

    private final CameraStreamProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskScheduler taskScheduler;

    public AiStreamClient(CameraStreamProperties properties, ObjectMapper objectMapper, TaskScheduler taskScheduler) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.taskScheduler = taskScheduler;
    }

    /** 송출 중인 세션 전체({@code sessionId} → 상태). AI가 목록에 넣지 않은 세션은 송출하지 않는 것이다. */
    public Map<String, AiLiveStream> fetchLiveStreams() {
        AiResponse response = get("live-streams", null, uri(LIVE_STREAMS_PATH), MAX_JSON_BYTES);
        requireSuccess(response, "live-streams", null);
        JsonNode data = readData(response, "live-streams", null);
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
    }

    /** 세션 상태. AI가 세션을 모르면(송출 안 함) 빈 값. */
    public Optional<AiStreamStatus> fetchStatus(String sessionId) {
        AiResponse response = get("status", sessionId, uri(LIVE_STREAMS_PATH + "/{id}/status", sessionId), MAX_JSON_BYTES);
        if (response.status() == HttpStatus.NOT_FOUND.value()) {
            return Optional.empty();
        }
        requireSuccess(response, "status", sessionId);
        JsonNode data = readData(response, "status", sessionId);
        if (!data.isObject()) {
            return Optional.empty();
        }
        return Optional.of(new AiStreamStatus(
                textOrNull(data, "status"),
                parseAiTime(textOrNull(data, "lastFrameAt")),
                data.hasNonNull("fps") ? data.get("fps").asDouble() : null,
                data.path("isAnalyzing").asBoolean(false)));
    }

    /** 최신 프레임(JPEG). 아직 프레임이 없거나 세션이 없으면 빈 값. */
    public Optional<byte[]> fetchLatestFrame(String sessionId) {
        AiResponse response = get("latest-frame", sessionId,
                uri(LIVE_STREAMS_PATH + "/{id}/latest-frame", sessionId), properties.getMaxFrameBytes());
        if (response.status() == HttpStatus.NOT_FOUND.value()) {
            return Optional.empty();
        }
        requireSuccess(response, "latest-frame", sessionId);
        return Optional.of(response.body());
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
            // 끝없는 응답이라 전체 시간 제한 대신 읽기 1회 무수신 제한만 둔다
            connection = open(uri(LIVE_STREAMS_PATH + "/{id}/mjpeg", sessionId), properties.getStreamIdleTimeout());

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

    /**
     * 목록·상태·스냅샷 공통 GET - 연결·헤더 대기·본문 수신 <b>전체</b>를 {@code callTimeout} 안에 끝낸다(2026-10-04 점검 L-3).
     *
     * <p>읽기 제한({@code readTimeout})은 읽기 한 번의 제한이라 AI가 조금씩 흘려 보내면 요청 스레드가 무한정 묶인다.
     * 그래서 마감 시각에 스케줄러가 연결을 끊는다 - 끊으면 막혀 있던 읽기가 예외로 빠져나온다. Spring 응답 래퍼를
     * 쓰지 않는 이유는 MJPEG와 같다(그 {@code close()}는 남은 본문을 끝까지 읽어 중단한 의미가 없어진다).</p>
     */
    private AiResponse get(String call, String sessionId, URI uri, int maxBytes) {
        requireApiKey();
        HttpURLConnection connection = null;
        ScheduledFuture<?> deadline = null;
        AtomicBoolean timedOut = new AtomicBoolean();
        long deadlineNanos = System.nanoTime() + properties.getCallTimeout().toNanos();
        try {
            connection = open(uri, properties.getReadTimeout());
            HttpURLConnection target = connection;
            // 헤더 대기 단계는 연결을 끊으면 바로 빠져나온다
            deadline = taskScheduler.schedule(() -> {
                timedOut.set(true);
                target.disconnect();
            }, Instant.now().plus(properties.getCallTimeout()));

            int status = connection.getResponseCode();
            if (timedOut.get()) {
                throw new IOException("call deadline reached");
            }
            if (status < 200 || status >= 300) {
                return new AiResponse(status, new byte[0]);
            }
            try (InputStream body = connection.getInputStream()) {
                return new AiResponse(status, readBounded(body, maxBytes, deadlineNanos, timedOut, call, sessionId));
            }
        } catch (IOException e) {
            if (timedOut.get()) {
                log.warn("[CAMERA-STREAM] AI 호출 시간 초과: call={}, sessionId={}, limit={}",
                        call, sessionId, properties.getCallTimeout());
                throw new AiStreamUnavailableException("AI 호출 시간 초과: " + call, e);
            }
            throw unavailable(call, sessionId, e);
        } finally {
            if (deadline != null) {
                deadline.cancel(false);
            }
            if (connection != null) {
                connection.disconnect();   // 본문을 다 읽었거나 중단했다 - 연결을 재사용하지 않는다
            }
        }
    }

    /**
     * 본문을 덩어리 단위로 읽으며 읽을 때마다 마감·크기를 확인한다.
     *
     * <p>본문을 읽는 중에는 다른 스레드의 {@code disconnect()}가 읽기를 깨우지 못한다(JDK 동작, 테스트로 확인). 그래서
     * 마감 확인을 읽기 사이에 둔다 - 최악의 경우는 마감 + 읽기 1회 제한({@code readTimeout})이다. 마감에 끊긴 스트림은
     * 예외 대신 EOF로 끝나기도 해서, 마감이 지났으면 정상 종료여도 잘린 본문으로 보고 실패시킨다.</p>
     */
    private byte[] readBounded(InputStream body, int maxBytes, long deadlineNanos, AtomicBoolean timedOut,
                               String call, String sessionId) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8 * 1024];
        while (true) {
            if (System.nanoTime() > deadlineNanos) {
                timedOut.set(true);
            }
            if (timedOut.get()) {
                throw new IOException("call deadline reached");
            }
            int read = body.read(buffer);
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            if (out.size() > maxBytes) {
                log.warn("[CAMERA-STREAM] AI 응답이 크기 상한을 넘음: call={}, sessionId={}, max={}", call, sessionId, maxBytes);
                throw new AiStreamUnavailableException("AI 응답 크기 초과: " + call);
            }
        }
        if (timedOut.get() || System.nanoTime() > deadlineNanos) {
            timedOut.set(true);
            throw new IOException("call deadline reached");
        }
        return out.toByteArray();
    }

    /** AI 연결 준비. 리다이렉트를 따라가지 않는다(점검 L-2 - 30x로 다른 호스트를 가리키면 키 헤더가 따라간다). */
    private HttpURLConnection open(URI uri, Duration readTimeout) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        connection.setReadTimeout((int) readTimeout.toMillis());
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty(API_KEY_HEADER, properties.getApiKey());
        return connection;
    }

    private void requireApiKey() {
        if (!StringUtils.hasText(properties.getApiKey())) {
            log.warn("[CAMERA-STREAM] AI_API_KEY 미설정 - 영상 중계 불가(.env.dev에 AI_API_KEY 주입 필요)");
            throw new AiStreamUnavailableException("AI API Key 미설정");
        }
    }

    private URI uri(String path, Object... variables) {
        // encode()를 치환 전에 두어 변수 값의 '/'까지 인코딩한다(세션 ID로 다른 AI 경로를 가리킬 수 없게)
        return UriComponentsBuilder.fromUriString(properties.getAiBaseUrl())
                .path(path)
                .encode()
                .buildAndExpand(variables)
                .toUri();
    }

    private void requireSuccess(AiResponse response, String call, String sessionId) {
        if (response.status() < 200 || response.status() >= 300) {
            log.warn("[CAMERA-STREAM] AI 응답 실패: call={}, sessionId={}, status={}", call, sessionId, response.status());
            throw new AiStreamUnavailableException("AI 응답 실패: " + call + " " + response.status());
        }
    }

    /** AI 공통 응답 {@code {success, message, data}}에서 data만 꺼낸다. */
    private JsonNode readData(AiResponse response, String call, String sessionId) {
        try {
            return objectMapper.readTree(response.body()).path("data");
        } catch (IOException e) {
            throw unavailable(call, sessionId, e);
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

    /** AI 응답(상태 코드 + 상한 안에서 읽은 본문). 2xx가 아니면 본문은 비어 있다. */
    private record AiResponse(int status, byte[] body) {}

    /** AI 목록의 세션 1건. */
    public record AiLiveStream(String sessionId, String status, OffsetDateTime lastFrameAt) {}

    /** AI 세션 상태. {@code viewerCount}는 다른 시청자 수가 드러나서 옮기지 않는다. */
    public record AiStreamStatus(String status, OffsetDateTime lastFrameAt, Double fps, boolean isAnalyzing) {}
}

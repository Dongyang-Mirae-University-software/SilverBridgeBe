package kr.silverbridge.main.domain.companion.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * AI 서버 안부 말벗 클라이언트(2026-11-04). AI {@code /companion/*}(재희 AI 계약 {@code feat/companion-ai-part}).
 *
 * <p>키는 {@code X-API-Key} 헤더로만 보낸다. 대답 글·음성·사진·받아 적은 글·AI 응답 문장이 오가므로 <b>요청·응답 본문은
 * 로그에 남기지 않는다</b> - 로그에는 상태 코드·허용 목록 안의 AI 오류 코드·예외 클래스명까지만. AI 오류 문구는 읽지 않는다.
 * 리다이렉트는 따라가지 않고(키 유출 방지) 호출 전체 시간 제한을 둔다 - 마감이 되면 연결을 끊는다(OCR·챗 중계와 같은 방식).</p>
 *
 * <p>AI 성공 응답은 {@code {success, message, data}}이고 이 클래스는 {@code data}만 돌려준다. 실패 응답은
 * {@code {code, errorCode}}이며 {@code code}가 아래 허용 목록에 있을 때만 해당 오류로 바꾼다. 그 밖(401 키 불일치,
 * FastAPI 422 {@code {detail}}, 모르는 코드)은 503 {@code COMPANION_UNAVAILABLE}이다.</p>
 */
@Slf4j
@Component
public class AiCompanionClient {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final int MAX_ERROR_BODY_BYTES = 8 * 1024;
    /** AI 대화 번호를 경로에 넣기 전 형식 확인(경로 조작 방지). */
    private static final Pattern DIALOGUE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern WARD_ID = Pattern.compile("^[A-Za-z0-9]{1,16}$");

    private static final Map<String, ErrorCode> AI_ERROR_CODES = Map.ofEntries(
            Map.entry("DIALOGUE_NOT_FOUND", ErrorCode.COMPANION_SESSION_LOST),
            Map.entry("DIALOGUE_ENDED", ErrorCode.COMPANION_SESSION_ENDED),
            Map.entry("ANSWER_EMPTY", ErrorCode.COMPANION_ANSWER_EMPTY),
            Map.entry("ANSWER_TOO_LONG", ErrorCode.COMPANION_ANSWER_TOO_LONG),
            Map.entry("AUDIO_MISSING", ErrorCode.COMPANION_AUDIO_INVALID),
            Map.entry("AUDIO_FORMAT_UNSUPPORTED", ErrorCode.COMPANION_AUDIO_INVALID),
            Map.entry("AUDIO_TOO_LARGE", ErrorCode.COMPANION_AUDIO_TOO_LARGE),
            Map.entry("FRAMES_TOO_MANY", ErrorCode.COMPANION_FRAME_TOO_LARGE),
            Map.entry("FRAME_TOO_LARGE", ErrorCode.COMPANION_FRAME_TOO_LARGE),
            Map.entry("FRAME_FORMAT_UNSUPPORTED", ErrorCode.COMPANION_FRAME_INVALID),
            Map.entry("STT_UNAVAILABLE", ErrorCode.COMPANION_STT_UNAVAILABLE));
    /** 로그에 남겨도 되는 AI 오류 코드(고정 영문 값). 이 밖의 값은 "unknown"으로 남긴다. */
    private static final Set<String> LOGGABLE_AI_CODES = Set.of(
            "DIALOGUE_NOT_FOUND", "DIALOGUE_ENDED", "ANSWER_EMPTY", "ANSWER_TOO_LONG", "AUDIO_MISSING",
            "AUDIO_FORMAT_UNSUPPORTED", "AUDIO_TOO_LARGE", "FRAMES_TOO_MANY", "FRAME_TOO_LARGE",
            "FRAME_FORMAT_UNSUPPORTED", "STT_UNAVAILABLE", "AUTH_INVALID_KEY",
            "SLOT_INVALID", "QUESTION_NOT_SET", "BACKEND_UNAVAILABLE");

    private final CompanionProperties properties;
    private final ObjectMapper objectMapper;
    /**
     * 호출 마감 전용 스레드. 빈으로 등록하지 않는다 - {@code TaskScheduler} 빈이 하나 더 생기면 {@code @Scheduled} 전체가
     * 이 풀로 옮겨 간다(OCR 클라이언트와 같은 이유). 동시 호출이 최대 8건이라 2스레드면 충분하다.
     */
    private final ScheduledExecutorService deadlineExecutor;

    public AiCompanionClient(CompanionProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        AtomicInteger seq = new AtomicInteger();
        this.deadlineExecutor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "companion-deadline-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        deadlineExecutor.shutdownNow();
    }

    /** 대화를 연다. {@code sessionId}는 백엔드 대화 번호, {@code questionId}는 질문 번호다(결정 A - 둘은 다르다). */
    public JsonNode openDialogue(String wardId, long sessionId, String slot, String question, long questionId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("wardId", wardId);
        body.put("sessionId", String.valueOf(sessionId));
        body.put("slot", slot);
        body.put("question", question);
        body.put("questionId", String.valueOf(questionId));
        return call("POST", "/companion/dialogues", "application/json", List.of(toJson(body)),
                properties.getCallTimeout());
    }

    public JsonNode replyText(String dialogueId, String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("text", text);
        return call("POST", dialoguePath(dialogueId, "/reply"), "application/json", List.of(toJson(body)),
                properties.getCallTimeout());
    }

    /** 음성 1개 + 표정 사진 0~5장. 형식은 호출부가 매직 바이트로 확인한 값이다. */
    public JsonNode replyAudio(String dialogueId, byte[] audio, CompanionMedia audioFormat,
                               List<byte[]> frames, List<CompanionMedia> frameFormats) {
        String boundary = "----sb-companion-" + UUID.randomUUID();
        List<byte[]> parts = new ArrayList<>();
        parts.add(partHead(boundary, "audio", audioFormat));
        parts.add(audio);
        for (int i = 0; i < frames.size(); i++) {
            parts.add(("\r\n").getBytes(StandardCharsets.US_ASCII));
            parts.add(partHead(boundary, "frame", frameFormats.get(i)));
            parts.add(frames.get(i));
        }
        parts.add(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return call("POST", dialoguePath(dialogueId, "/reply/audio"), "multipart/form-data; boundary=" + boundary,
                parts, properties.getCallTimeout());
    }

    /** 대화를 끝내고 결과를 받는다. 같은 대화에 다시 불러도 같은 결과가 온다(AI 계약). */
    public JsonNode finish(String dialogueId) {
        return call("POST", dialoguePath(dialogueId, "/finish"), "application/json", List.of(new byte[0]),
                properties.getCallTimeout());
    }

    /** 탈퇴 피보호자의 AI 쪽 데이터(메모리 대화·검수 목록)를 지운다. 실패는 예외로 알린다(호출부가 재시도). */
    public void deleteWardData(String wardId) {
        if (!WARD_ID.matcher(wardId).matches()) {
            throw new IllegalArgumentException("ward id format");
        }
        call("DELETE", "/companion/wards/" + wardId + "/data", null, List.of(), properties.getPurgeCallTimeout());
    }

    private String dialoguePath(String dialogueId, String suffix) {
        if (dialogueId == null || !DIALOGUE_ID.matcher(dialogueId).matches()) {
            log.warn("[COMPANION] AI 대화 번호 형식 이상");
            throw new CustomException(ErrorCode.COMPANION_UNAVAILABLE);
        }
        return "/companion/dialogues/" + dialogueId + suffix;
    }

    private JsonNode call(String method, String path, String contentType, List<byte[]> parts, Duration timeout) {
        if (!properties.isConfigured()) {
            log.warn("[COMPANION] COMPANION_AI_BASE_URL·COMPANION_AI_API_KEY 미설정 - 말벗 AI 호출 불가");
            throw new CustomException(ErrorCode.COMPANION_DISABLED);
        }
        URI uri = UriComponentsBuilder.fromUriString(properties.getAiBaseUrl()).path(path).build().toUri();
        long length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }

        HttpURLConnection connection = null;
        ScheduledFuture<?> deadline = null;
        AtomicBoolean timedOut = new AtomicBoolean();
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        try {
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
            connection.setReadTimeout((int) timeout.toMillis());
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty(API_KEY_HEADER, properties.getApiKey());
            connection.setRequestProperty("Accept", "application/json");
            HttpURLConnection target = connection;
            deadline = deadlineExecutor.schedule(() -> {
                timedOut.set(true);
                target.disconnect();
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);

            if (contentType != null) {
                connection.setRequestProperty("Content-Type", contentType);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(length);
                try (OutputStream out = connection.getOutputStream()) {
                    for (byte[] part : parts) {
                        out.write(part);
                    }
                }
            }

            int status = connection.getResponseCode();
            if (timedOut.get()) {
                throw new IOException("call deadline reached");
            }
            if (status >= 200 && status < 300) {
                try (InputStream in = connection.getInputStream()) {
                    return unwrap(readBounded(in, properties.getMaxReplyBytes(), deadlineNanos, timedOut));
                }
            }
            throw mapFailure(status, readErrorCode(connection, deadlineNanos, timedOut));
        } catch (IOException e) {
            if (timedOut.get()) {
                log.warn("[COMPANION] AI 호출 시간 초과: limit={}", timeout);
                throw new CustomException(ErrorCode.COMPANION_TIMEOUT);
            }
            // 예외 원문에는 접속 주소가 섞일 수 있어 클래스명만 남긴다
            log.warn("[COMPANION] AI 호출 실패: error={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.COMPANION_UNAVAILABLE);
        } finally {
            if (deadline != null) {
                deadline.cancel(false);
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private CustomException mapFailure(int status, String aiCode) {
        ErrorCode mapped = aiCode == null ? null : AI_ERROR_CODES.get(aiCode);
        String loggable = aiCode != null && LOGGABLE_AI_CODES.contains(aiCode) ? aiCode : "unknown";
        if (mapped != null) {
            log.info("[COMPANION] AI 거절: status={}, aiCode={}", status, loggable);
            return new CustomException(mapped);
        }
        log.error("[COMPANION] AI 응답 실패: status={}, aiCode={}", status, loggable);
        return new CustomException(ErrorCode.COMPANION_UNAVAILABLE);
    }

    /** 오류 본문에서 {@code code}만 꺼낸다. 문구({@code message}·{@code detail})는 읽지 않는다. */
    private String readErrorCode(HttpURLConnection connection, long deadlineNanos, AtomicBoolean timedOut)
            throws IOException {
        InputStream err = connection.getErrorStream();
        if (err == null) {
            return null;
        }
        try (InputStream in = err) {
            byte[] raw = readBounded(in, MAX_ERROR_BODY_BYTES, deadlineNanos, timedOut);
            JsonNode node = objectMapper.readTree(raw);
            JsonNode code = node == null ? null : node.get("code");
            return code != null && code.isTextual() ? code.asText() : null;
        } catch (CustomException | IOException e) {
            if (timedOut.get()) {
                throw new IOException("call deadline reached");
            }
            return null;
        }
    }

    private JsonNode unwrap(byte[] raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            JsonNode data = node == null ? null : node.get("data");
            if (data == null || !data.isObject()) {
                log.warn("[COMPANION] AI 응답 형식 이상(data 객체 없음)");
                throw new CustomException(ErrorCode.COMPANION_UNAVAILABLE);
            }
            return data;
        } catch (IOException e) {
            log.warn("[COMPANION] AI 응답 해석 실패: error={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.COMPANION_UNAVAILABLE);
        }
    }

    /** 덩어리마다 마감·크기를 확인하며 읽는다(마감이 지나면 EOF여도 잘린 본문으로 보고 실패시킨다). */
    private static byte[] readBounded(InputStream in, int maxBytes, long deadlineNanos, AtomicBoolean timedOut)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8 * 1024];
        while (true) {
            if (System.nanoTime() > deadlineNanos) {
                timedOut.set(true);
            }
            if (timedOut.get()) {
                throw new IOException("call deadline reached");
            }
            int read = in.read(buffer);
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            if (out.size() > maxBytes) {
                log.warn("[COMPANION] AI 응답이 크기 상한을 넘음: max={}", maxBytes);
                throw new CustomException(ErrorCode.COMPANION_UNAVAILABLE);
            }
        }
        if (timedOut.get() || System.nanoTime() > deadlineNanos) {
            timedOut.set(true);
            throw new IOException("call deadline reached");
        }
        return out.toByteArray();
    }

    private byte[] toJson(ObjectNode body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.COMPANION_UNAVAILABLE);
        }
    }

    /** 파일 이름은 형식별 고정값이다 - 사용자가 올린 원래 이름을 넘기지 않는다. */
    private static byte[] partHead(String boundary, String name, CompanionMedia format) {
        return ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + format.fileName() + "\"\r\n"
                + "Content-Type: " + format.contentType() + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    }
}

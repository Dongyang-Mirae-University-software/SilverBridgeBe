package kr.silverbridge.main.domain.checkin.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import kr.silverbridge.main.domain.checkin.config.CheckinProperties;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 안부 말벗 AI 클라이언트(2026-11-01). AI 계약 v0 {@code /api/v1/checkin} - 시작·턴(multipart)·정리·탈퇴 삭제.
 *
 * <p>약 봉투 OCR·챗 중계와 같은 규칙이다: 키는 {@code X-API-Key} 헤더로만, 리다이렉트는 따라가지 않고, 호출 전체 마감은
 * 전용 데몬 스레드에서 연결을 끊는다(빈으로 등록하지 않는다 - {@code TaskScheduler} 빈이 늘면 {@code @Scheduled}가 옮겨 간다).
 * <b>요청·응답 본문(음성·사진·받아쓴 글자·AI 답)은 로그에 남기지 않는다</b> - 상태 코드·호출 이름·예외 클래스명까지만.</p>
 *
 * <p>AI 오류는 본문의 {@code errorCode}만 읽는다(고정 코드 허용 목록, {@code message}는 읽지 않음). 같은 422·413 안에서
 * "못 알아들음"과 "파일을 못 읽음", "너무 큼"과 "너무 김"을 사용자에게 다르게 안내해야 해서다. 모르는 코드는 상태 코드로만 판단한다.</p>
 */
@Slf4j
@Component
public class CheckinAiClient {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String BASE_PATH = "/api/v1/checkin";
    private static final int MAX_ERROR_BYTES = 4 * 1024;
    private static final Set<String> KNOWN_ERROR_CODES = Set.of(
            "CHECKIN_DISABLED", "CHECKIN_BUSY", "SESSION_NOT_FOUND", "SESSION_CONFLICT", "SESSION_FINISHED",
            "TURN_OUT_OF_ORDER", "TURN_LIMIT", "AUDIO_EMPTY", "AUDIO_UNREADABLE", "SPEECH_NOT_RECOGNIZED",
            "FRAME_NOT_JPEG", "AUDIO_TOO_LARGE", "AUDIO_TOO_LONG", "FRAME_TOO_LARGE", "FRAMES_TOO_MANY");

    private final CheckinProperties properties;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService deadlineExecutor;

    public CheckinAiClient(CheckinProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        AtomicInteger seq = new AtomicInteger();
        this.deadlineExecutor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "checkin-deadline-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        deadlineExecutor.shutdownNow();
    }

    /** 대화 시작. 같은 요청 재전송은 AI가 같은 답을 준다. */
    public void start(String sessionId, String userId, String slot, String question) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("sessionId", sessionId);
        body.put("userId", userId);
        body.put("slot", slot);
        body.put("question", question);
        AiReply reply = exchange("start", "POST", uri("/sessions"), "application/json",
                List.of(json(body)), properties.getStartTimeout());
        requireSuccess("start", reply);
    }

    /** 턴 하나. 사진은 호출부가 동의·형식·크기를 확인한 것만 넘긴다(빈 목록이면 사진 파트 없음). */
    public JsonNode turn(String sessionId, String userId, int turnNo, AudioFormat format, byte[] audio,
                         List<byte[]> frames) {
        String boundary = "----sb-checkin-" + UUID.randomUUID();
        List<byte[]> parts = new ArrayList<>();
        parts.add(textPart(boundary, "userId", userId));
        parts.add(textPart(boundary, "turnNo", Integer.toString(turnNo)));
        parts.add(fileHead(boundary, "audio", format.fileName(), format.contentType()));
        parts.add(audio);
        for (int i = 0; i < frames.size(); i++) {
            parts.add(("\r\n").getBytes(StandardCharsets.US_ASCII));
            parts.add(fileHead(boundary, "frames", "frame" + (i + 1) + ".jpg", "image/jpeg"));
            parts.add(frames.get(i));
        }
        parts.add(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        AiReply reply = exchange("turn", "POST", uri("/sessions/{id}/turns", sessionId),
                "multipart/form-data; boundary=" + boundary, parts, properties.getTurnTimeout());
        requireSuccess("turn", reply);
        return dataOf("turn", reply);
    }

    /** 대화 정리. {@code selfReport}는 AI 한글 값 또는 null. */
    public JsonNode finish(String sessionId, String userId, String selfReport, String reason) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("userId", userId);
        if (selfReport == null) {
            body.putNull("selfReport");
        } else {
            body.put("selfReport", selfReport);
        }
        body.put("reason", reason);
        AiReply reply = exchange("finish", "POST", uri("/sessions/{id}/finish", sessionId), "application/json",
                List.of(json(body)), properties.getFinishTimeout());
        requireSuccess("finish", reply);
        return dataOf("finish", reply);
    }

    /**
     * 회원의 말벗 대화 기록 삭제(탈퇴 정리). AI {@code DELETE /logs?userId=}. 200만 성공이다(404·405 = 옛 AI 서버라 지우지 못한 것).
     * 실패는 {@link CustomException} - 호출부(탈퇴 리스너)가 삼킨다.
     */
    public void deleteLogs(String userId) {
        URI uri = UriComponentsBuilder.fromUriString(properties.getAiBaseUrl()).path(BASE_PATH + "/logs")
                .queryParam("userId", "{userId}").encode().buildAndExpand(userId).toUri();
        AiReply reply = exchange("logs-delete", "DELETE", uri, null, List.of(), properties.getPurgeTimeout());
        if (reply.status() != 200) {
            log.warn("[CHECKIN-AI] 기록 삭제 응답 이상: status={}", reply.status());
            throw new CustomException(ErrorCode.CHECKIN_UNAVAILABLE);
        }
    }

    // ---------------------------------------------------------------- 내부

    private URI uri(String path, Object... vars) {
        return UriComponentsBuilder.fromUriString(properties.getAiBaseUrl()).path(BASE_PATH + path)
                .encode().buildAndExpand(vars).toUri();
    }

    private byte[] json(JsonNode node) {
        try {
            return objectMapper.writeValueAsBytes(node);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.CHECKIN_UNAVAILABLE);
        }
    }

    private void requireSuccess(String call, AiReply reply) {
        if (reply.status() >= 200 && reply.status() < 300) {
            return;
        }
        throw mapFailure(call, reply);
    }

    /** 상태 코드 + 고정 오류 코드만으로 바꾼다. 사용자 탓(음성·사진)이면 4xx, 그 밖(키 불일치 401·BE와 어긋난 409 포함)은 503. */
    CustomException mapFailure(String call, AiReply reply) {
        String code = reply.errorCode();
        int status = reply.status();
        log.info("[CHECKIN-AI] AI 실패 응답: call={}, status={}, code={}", call, status, code);
        if ("SPEECH_NOT_RECOGNIZED".equals(code) || "AUDIO_EMPTY".equals(code)) {
            return new CustomException(ErrorCode.CHECKIN_SPEECH_NOT_RECOGNIZED);
        }
        if ("AUDIO_UNREADABLE".equals(code)) {
            return new CustomException(ErrorCode.CHECKIN_INVALID_AUDIO);
        }
        if ("AUDIO_TOO_LONG".equals(code)) {
            return new CustomException(ErrorCode.CHECKIN_AUDIO_TOO_LONG);
        }
        if ("AUDIO_TOO_LARGE".equals(code)) {
            return new CustomException(ErrorCode.CHECKIN_AUDIO_TOO_LARGE);
        }
        if ("FRAME_NOT_JPEG".equals(code) || "FRAME_TOO_LARGE".equals(code) || "FRAMES_TOO_MANY".equals(code)) {
            return new CustomException(ErrorCode.CHECKIN_INVALID_FRAME);
        }
        if ("CHECKIN_BUSY".equals(code) || status == 429) {
            return new CustomException(ErrorCode.CHECKIN_BUSY);
        }
        if (status == 413) {
            return new CustomException(ErrorCode.CHECKIN_AUDIO_TOO_LARGE);
        }
        log.warn("[CHECKIN-AI] AI 응답 실패: call={}, status={}, code={}", call, status, code);
        return new CustomException(ErrorCode.CHECKIN_UNAVAILABLE);
    }

    private JsonNode dataOf(String call, AiReply reply) {
        try {
            JsonNode data = objectMapper.readTree(reply.body()).path("data");
            if (!data.isObject()) {
                log.warn("[CHECKIN-AI] AI 응답에 data 없음: call={}", call);
                throw new CustomException(ErrorCode.CHECKIN_UNAVAILABLE);
            }
            return data;
        } catch (IOException e) {
            log.warn("[CHECKIN-AI] AI 응답 해석 실패: call={}, error={}", call, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.CHECKIN_UNAVAILABLE);
        }
    }

    private AiReply exchange(String call, String method, URI uri, String contentType, List<byte[]> parts,
                             Duration timeout) {
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
                long length = 0;
                for (byte[] part : parts) {
                    length += part.length;
                }
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
                    return new AiReply(status, readBounded(in, deadlineNanos, timedOut, properties.getMaxReplyBytes()),
                            null);
                }
            }
            return new AiReply(status, null, readErrorCode(connection));
        } catch (IOException e) {
            if (timedOut.get()) {
                log.warn("[CHECKIN-AI] AI 호출 시간 초과: call={}, limit={}", call, timeout);
                throw new CustomException(ErrorCode.CHECKIN_TIMEOUT);
            }
            // 예외 원문에는 접속 주소가 섞일 수 있어 클래스명만 남긴다
            log.warn("[CHECKIN-AI] AI 호출 실패: call={}, error={}", call, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.CHECKIN_UNAVAILABLE);
        } finally {
            if (deadline != null) {
                deadline.cancel(false);
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 오류 본문에서 허용 목록의 {@code errorCode}만 꺼낸다. 읽지 못하거나 모르는 코드면 null. message는 보지 않는다. */
    private String readErrorCode(HttpURLConnection connection) {
        try (InputStream in = connection.getErrorStream()) {
            if (in == null) {
                return null;
            }
            byte[] raw = in.readNBytes(MAX_ERROR_BYTES);
            JsonNode code = objectMapper.readTree(raw).path("errorCode");
            return code.isTextual() && KNOWN_ERROR_CODES.contains(code.asText()) ? code.asText() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] readBounded(InputStream in, long deadlineNanos, AtomicBoolean timedOut, int maxBytes)
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
                log.warn("[CHECKIN-AI] AI 응답이 크기 상한을 넘음: max={}", maxBytes);
                throw new CustomException(ErrorCode.CHECKIN_UNAVAILABLE);
            }
        }
        return out.toByteArray();
    }

    private static byte[] textPart(String boundary, String name, String value) {
        return ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    /** 파일 이름은 형식별 고정값이다 - 사용자가 올린 원래 이름을 넘기지 않는다. */
    private static byte[] fileHead(String boundary, String name, String fileName, String contentType) {
        return ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    record AiReply(int status, byte[] body, String errorCode) {
    }

    /** 매직 바이트로 확인한 음성 형식. AI가 받는 네 가지뿐이다. */
    public enum AudioFormat {
        WAV("audio/wav", "audio.wav"),
        WEBM("audio/webm", "audio.webm"),
        OGG("audio/ogg", "audio.ogg"),
        M4A("audio/mp4", "audio.m4a");

        private final String contentType;
        private final String fileName;

        AudioFormat(String contentType, String fileName) {
            this.contentType = contentType;
            this.fileName = fileName;
        }

        public String contentType() {
            return contentType;
        }

        public String fileName() {
            return fileName;
        }

        /** 파일 앞부분 바이트로 판별한다. 선언된 Content-Type·확장자는 믿지 않는다. 아니면 {@code null}. */
        public static AudioFormat detect(byte[] h) {
            if (h == null || h.length < 12) {
                return null;
            }
            if (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                    && h[8] == 'W' && h[9] == 'A' && h[10] == 'V' && h[11] == 'E') {
                return WAV;
            }
            if ((h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45 && (h[2] & 0xFF) == 0xDF && (h[3] & 0xFF) == 0xA3) {
                return WEBM;
            }
            if (h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S') {
                return OGG;
            }
            if (h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') {
                return M4A;
            }
            return null;
        }
    }

    /** JPEG 매직 바이트(FF D8 FF). */
    public static boolean isJpeg(byte[] h) {
        return h != null && h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF;
    }
}

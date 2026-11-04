package kr.silverbridge.main.domain.chat.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import kr.silverbridge.main.domain.chat.config.ChatSttProperties;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 챗봇 마이크 음성 인식 AI 클라이언트(2026-11-04). AI의 {@code POST /companion/stt}에 음성 파일 1개를 보내고
 * 받아 적은 글자({@code data.text})만 돌려준다.
 *
 * <p><b>음성과 글자는 로그에 남기지 않는다</b> - 로그에는 크기·상태 코드·AI 오류 코드(고정 목록)·예외 클래스명까지만.
 * AI 오류 문구는 읽지 않고 {@code code}만 본다. 키는 {@code X-API-Key} 헤더로만 보내고, 리다이렉트는 따라가지 않으며
 * (키 유출 방지), 호출 전체 시간 제한을 둔다 - 마감이 되면 연결을 끊는다(챗 중계 {@link AiChatClient}와 같은 방식).
 * 형식 판별은 AI가 파일 머리로 한다. 업로드 이름은 고정값이다(원래 파일 이름에 개인정보가 섞일 수 있다).</p>
 */
@Slf4j
@Component
public class AiSttClient {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String STT_PATH = "/companion/stt";
    private static final int MAX_ERROR_BODY_BYTES = 8 * 1024;

    /** AI 오류 코드 → BE 오류 코드. 목록에 없는 코드(키 오류 등)는 503 {@code CHAT_STT_UNAVAILABLE}. */
    private static final Map<String, ErrorCode> AI_ERROR_CODES = Map.of(
            "AUDIO_TOO_LARGE", ErrorCode.CHAT_STT_AUDIO_TOO_LARGE,
            "AUDIO_TOO_LONG", ErrorCode.CHAT_STT_AUDIO_TOO_LARGE,
            "AUDIO_MISSING", ErrorCode.CHAT_STT_AUDIO_INVALID,
            "AUDIO_FORMAT_UNSUPPORTED", ErrorCode.CHAT_STT_AUDIO_INVALID,
            "AUDIO_UNREADABLE", ErrorCode.CHAT_STT_AUDIO_INVALID,
            "STT_BUSY", ErrorCode.CHAT_STT_BUSY,
            "STT_UNAVAILABLE", ErrorCode.CHAT_STT_UNAVAILABLE);

    private final ChatSttProperties properties;
    private final ObjectMapper objectMapper;
    /** 호출 마감(연결 끊기) 전용 스레드. 공용 스케줄러를 쓰지 않는 이유는 {@link AiChatClient}와 같다. */
    private final ScheduledExecutorService deadlineExecutor;

    public AiSttClient(ChatSttProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        AtomicInteger seq = new AtomicInteger();
        this.deadlineExecutor = Executors.newScheduledThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable, "chat-stt-deadline-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        deadlineExecutor.shutdownNow();
    }

    /** 음성을 글자로 바꾼다. 못 알아들으면 빈 글자다. */
    public String transcribe(byte[] audio) {
        if (!properties.isConfigured()) {
            log.warn("[CHAT-STT] CHAT_STT_AI_BASE_URL·CHAT_STT_API_KEY 미설정 - 음성 인식 불가");
            throw new CustomException(ErrorCode.CHAT_STT_DISABLED);
        }
        if (properties.getApiKey().chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f)) {
            // CRLF로 저장한 .env 등. 헤더에 실을 수 없는 값이라 원인만 고정 문구로 알린다(값은 남기지 않는다).
            log.warn("[CHAT-STT] CHAT_STT_API_KEY에 줄바꿈 등 제어 문자가 있음 - .env.dev 줄 끝(CRLF)을 확인할 것");
            throw new CustomException(ErrorCode.CHAT_STT_UNAVAILABLE);
        }
        String boundary = "----sb-stt-" + UUID.randomUUID().toString().replace("-", "");
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"audio\"; filename=\"audio\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        return call(boundary, head, audio, tail);
    }

    private String call(String boundary, byte[] head, byte[] audio, byte[] tail) {
        Duration timeout = properties.getCallTimeout();
        HttpURLConnection connection = null;
        ScheduledFuture<?> deadline = null;
        AtomicBoolean timedOut = new AtomicBoolean();
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        try {
            URI uri = UriComponentsBuilder.fromUriString(properties.getAiBaseUrl()).path(STT_PATH).build().toUri();
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
            connection.setReadTimeout((int) timeout.toMillis());
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty(API_KEY_HEADER, properties.getApiKey());
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            HttpURLConnection target = connection;
            deadline = deadlineExecutor.schedule(() -> {
                timedOut.set(true);
                target.disconnect();
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);

            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode((long) head.length + audio.length + tail.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(head);
                out.write(audio);
                out.write(tail);
            }

            int status = connection.getResponseCode();
            if (timedOut.get()) {
                throw new IOException("call deadline reached");
            }
            if (status >= 200 && status < 300) {
                try (InputStream in = connection.getInputStream()) {
                    return textOf(readBounded(in, properties.getMaxReplyBytes(), deadlineNanos, timedOut));
                }
            }
            throw mapFailure(status, readErrorCode(connection, deadlineNanos, timedOut));
        } catch (IOException e) {
            if (timedOut.get()) {
                log.warn("[CHAT-STT] AI 호출 시간 초과: limit={}", timeout);
            } else {
                // 예외 원문에는 접속 주소가 섞일 수 있어 클래스명만 남긴다
                log.warn("[CHAT-STT] AI 호출 실패: error={}", e.getClass().getSimpleName());
            }
            throw new CustomException(ErrorCode.CHAT_STT_UNAVAILABLE);
        } catch (CustomException e) {
            throw e;
        } catch (RuntimeException e) {
            // 잘못된 주소·종료 중 마감 예약 거절 등. 헤더 값 오류의 JDK 메시지에는 키 원문이 들어 있어 클래스명만 남긴다.
            log.warn("[CHAT-STT] AI 호출 준비 실패: error={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.CHAT_STT_UNAVAILABLE);
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
        if (mapped != null) {
            log.info("[CHAT-STT] AI 거절: status={}, aiCode={}", status, aiCode);
            return new CustomException(mapped);
        }
        // 키 오류(AUTH_INVALID_KEY)·경로 없음(옛 AI 서버) 등 - 설정 문제라 ERROR로 남긴다. 목록 밖 코드는 원문을 찍지 않는다.
        log.error("[CHAT-STT] AI 응답 실패: status={}, aiCode={}",
                status, "AUTH_INVALID_KEY".equals(aiCode) ? aiCode : "unknown");
        return new CustomException(ErrorCode.CHAT_STT_UNAVAILABLE);
    }

    /** 오류 본문에서 {@code code}만 꺼낸다. 문구는 읽지 않는다. */
    private String readErrorCode(HttpURLConnection connection, long deadlineNanos, AtomicBoolean timedOut)
            throws IOException {
        InputStream err = connection.getErrorStream();
        if (err == null) {
            return null;
        }
        try (InputStream in = err) {
            JsonNode node = objectMapper.readTree(readBounded(in, MAX_ERROR_BODY_BYTES, deadlineNanos, timedOut));
            JsonNode code = node == null ? null : node.get("code");
            return code != null && code.isTextual() ? code.asText() : null;
        } catch (CustomException | IOException e) {
            if (timedOut.get()) {
                throw new IOException("call deadline reached");
            }
            return null;
        }
    }

    private String textOf(byte[] raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            JsonNode text = node == null ? null : node.path("data").get("text");
            if (text == null || !text.isTextual()) {
                log.warn("[CHAT-STT] AI 응답 형식 이상(data.text 없음)");
                throw new CustomException(ErrorCode.CHAT_STT_UNAVAILABLE);
            }
            return text.asText();
        } catch (IOException e) {
            log.warn("[CHAT-STT] AI 응답 해석 실패: error={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.CHAT_STT_UNAVAILABLE);
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
                log.warn("[CHAT-STT] AI 응답이 크기 상한을 넘음: max={}", maxBytes);
                throw new CustomException(ErrorCode.CHAT_STT_UNAVAILABLE);
            }
        }
        if (timedOut.get() || System.nanoTime() > deadlineNanos) {
            timedOut.set(true);
            throw new IOException("call deadline reached");
        }
        return out.toByteArray();
    }
}

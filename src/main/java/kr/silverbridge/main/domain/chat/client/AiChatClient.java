package kr.silverbridge.main.domain.chat.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.chat.config.ChatRelayProperties;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 서버 챗 REST 클라이언트(백엔드 중계용, 2026-10-07).
 *
 * <p>AI 키는 {@code X-API-Key} 헤더로만 보낸다. <b>의료 상담 내용은 민감 정보</b>라 요청·응답 본문은 로그에 남기지
 * 않는다 - 로그에는 호출 종류·상태 코드·예외 클래스명까지만. AI의 오류 본문·문구도 읽어 내리지 않고 상태 코드로만
 * 판단한다(고정 문구만 응답). 리다이렉트는 따라가지 않고(키 헤더 유출 방지), 호출 전체 시간 제한을 둔다 -
 * 마감이 되면 연결을 끊는다(영상 중계의 {@code AiStreamClient}와 같은 방식, 챗 전용 값).</p>
 */
@Slf4j
@Component
public class AiChatClient {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String CHAT_PATH = "/api/v1/chat";

    private final ChatRelayProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskScheduler taskScheduler;

    public AiChatClient(ChatRelayProperties properties, ObjectMapper objectMapper, TaskScheduler taskScheduler) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.taskScheduler = taskScheduler;
    }

    /** 챗 전송. AI 응답의 {@code data}를 돌려준다. */
    public JsonNode send(byte[] requestBody) {
        AiResponse response = call("chat", "POST", chatUri(), requestBody,
                properties.getCallTimeout(), properties.getMaxReplyBytes());
        if (response.status() == 400 || response.status() == 422) {
            throw new CustomException(ErrorCode.CHAT_INVALID_REQUEST);
        }
        return dataOf(response, "chat");
    }

    /** 본인 기록 목록. {@code userId}는 호출부가 토큰에서 꺼낸 값이다. */
    public JsonNode logs(String userId) {
        AiResponse response = call("logs", "GET",
                logsUri(userId), null,
                properties.getLogsCallTimeout(), properties.getMaxLogsBytes());
        return dataOf(response, "logs");
    }

    /** 본인 기록 상세. 없거나 남의 기록이면 빈 값(AI가 둘을 구분하지 않고 404로 답한다). */
    public Optional<JsonNode> logDetail(String userId, long chatId) {
        AiResponse response = call("log-detail", "GET",
                logDetailUri(userId, chatId), null,
                properties.getLogsCallTimeout(), properties.getMaxReplyBytes());
        if (response.status() == 404) {
            return Optional.empty();
        }
        return Optional.of(dataOf(response, "log-detail"));
    }

    private JsonNode dataOf(AiResponse response, String call) {
        try {
            JsonNode data = objectMapper.readTree(response.body()).path("data");
            if (data.isMissingNode() || data.isNull()) {
                log.warn("[CHAT-RELAY] AI 응답에 data 없음: call={}", call);
                throw new CustomException(ErrorCode.CHAT_UNAVAILABLE);
            }
            return data;
        } catch (IOException e) {
            log.warn("[CHAT-RELAY] AI 응답 해석 실패: call={}, error={}", call, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.CHAT_UNAVAILABLE);
        }
    }

    /**
     * 공통 호출 - 2xx면 본문을 돌려주고, 400·404·422는 상태 그대로 돌려 호출부가 판단하며, 그 밖은 503이다.
     * 연결·헤더 대기·본문 수신 <b>전체</b>를 {@code callTimeout} 안에 끝낸다(마감 시 504).
     */
    private AiResponse call(String call, String method, URI uri, byte[] body, Duration callTimeout, int maxBytes) {
        requireApiKey();
        HttpURLConnection connection = null;
        ScheduledFuture<?> deadline = null;
        AtomicBoolean timedOut = new AtomicBoolean();
        long deadlineNanos = System.nanoTime() + callTimeout.toNanos();
        try {
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
            connection.setReadTimeout((int) callTimeout.toMillis());
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty(API_KEY_HEADER, properties.getApiKey());
            connection.setRequestProperty("Accept", "application/json");
            HttpURLConnection target = connection;
            deadline = taskScheduler.schedule(() -> {
                timedOut.set(true);
                target.disconnect();
            }, Instant.now().plus(callTimeout));

            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            }

            int status = connection.getResponseCode();
            if (timedOut.get()) {
                throw new IOException("call deadline reached");
            }
            if (status >= 200 && status < 300) {
                try (InputStream in = connection.getInputStream()) {
                    return new AiResponse(status, readBounded(in, maxBytes, deadlineNanos, timedOut, call));
                }
            }
            if (status == 400 || status == 404 || status == 422) {
                return new AiResponse(status, new byte[0]);
            }
            log.warn("[CHAT-RELAY] AI 응답 실패: call={}, status={}", call, status);
            throw new CustomException(ErrorCode.CHAT_UNAVAILABLE);
        } catch (IOException e) {
            if (timedOut.get()) {
                log.warn("[CHAT-RELAY] AI 호출 시간 초과: call={}, limit={}", call, callTimeout);
                throw new CustomException(ErrorCode.CHAT_TIMEOUT);
            }
            // 예외 원문에는 접속 주소가 섞일 수 있어 클래스명만 남긴다
            log.warn("[CHAT-RELAY] AI 호출 실패: call={}, error={}", call, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.CHAT_UNAVAILABLE);
        } finally {
            if (deadline != null) {
                deadline.cancel(false);
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 덩어리마다 마감·크기를 확인하며 읽는다(마감이 지나면 EOF여도 잘린 본문으로 보고 실패시킨다). */
    private byte[] readBounded(InputStream in, int maxBytes, long deadlineNanos, AtomicBoolean timedOut, String call)
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
                log.warn("[CHAT-RELAY] AI 응답이 크기 상한을 넘음: call={}, max={}", call, maxBytes);
                throw new CustomException(ErrorCode.CHAT_UNAVAILABLE);
            }
        }
        if (timedOut.get() || System.nanoTime() > deadlineNanos) {
            timedOut.set(true);
            throw new IOException("call deadline reached");
        }
        return out.toByteArray();
    }

    private void requireApiKey() {
        if (!StringUtils.hasText(properties.getApiKey())) {
            log.warn("[CHAT-RELAY] AI_API_KEY 미설정 - 챗 중계 불가(.env.dev에 AI_API_KEY 주입 필요)");
            throw new CustomException(ErrorCode.CHAT_UNAVAILABLE);
        }
    }

    /** 변수 값은 엄격히 인코딩한다(사용자 ID·번호로 다른 AI 경로나 쿼리를 가리킬 수 없게). */
    private UriComponentsBuilder base(String path) {
        return UriComponentsBuilder.fromUriString(properties.getAiBaseUrl()).path(path);
    }

    private URI chatUri() {
        return base(CHAT_PATH).build().toUri();
    }

    private URI logsUri(String userId) {
        return base(CHAT_PATH + "/logs").queryParam("userId", "{userId}").encode().buildAndExpand(userId).toUri();
    }

    private URI logDetailUri(String userId, long chatId) {
        return base(CHAT_PATH + "/logs/{id}").queryParam("userId", "{userId}")
                .encode().buildAndExpand(chatId, userId).toUri();
    }

    /** AI 응답(상태 코드 + 상한 안에서 읽은 본문). 2xx가 아니면 본문은 비어 있다. */
    private record AiResponse(int status, byte[] body) {}
}

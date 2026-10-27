package kr.silverbridge.main.domain.admin.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import kr.silverbridge.main.domain.chat.config.ChatRelayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 서버 {@code GET /health} 확인(관리자 대시보드 "AI 서비스 상태", 2026-10-27).
 *
 * <p>챗 중계가 붙는 바로 그 AI 서버({@code chat.relay.ai-base-url})를 본다. AI에는 챗봇 전용 상태 경로가 없어
 * 이 확인은 <b>챗봇을 서빙하는 AI 서버 프로세스가 응답하는가</b>까지만 답한다 - 모델(MedGemma·Claude) 호출이
 * 성공하는지는 보장하지 않는다.</p>
 *
 * <p>결과는 두 가지다: 정상({@code true}) / 확인 불가({@code false}). 네트워크 오류·시간 초과·5xx·형식 이상·키 미설정을
 * 모두 "확인 불가"로 묶는다 - 우리 쪽 경로 문제와 AI 장애를 구분할 수 없어 "중단"으로 단정하지 않는다.</p>
 *
 * <p>AI HTTP 클라이언트 공통 규칙: 키는 {@code X-API-Key} 헤더만, 리다이렉트 미추종, 호출 <b>전체</b> 제한(연결 3초·전체 5초),
 * 마감은 전용 데몬 스레드에서 연결을 끊는다(빈으로 등록하지 않는다 - {@code TaskScheduler} 빈이 늘면 {@code @Scheduled}가 옮겨 간다).
 * 로그에는 결과 코드·상태 코드·예외 클래스명만 남긴다.</p>
 */
@Slf4j
@Component
public class AiServerHealthClient {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String HEALTH_PATH = "/health";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);
    /** /health 본문은 수백 바이트다. 앞단이 큰 HTML을 돌려줘도 붙들지 않게 작게 자른다. */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final ChatRelayProperties properties;
    private final ObjectMapper objectMapper;
    private final Duration connectTimeout;
    private final Duration callTimeout;
    private final ScheduledExecutorService deadlineExecutor;

    public AiServerHealthClient(ChatRelayProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, CONNECT_TIMEOUT, CALL_TIMEOUT);
    }

    /** 테스트가 시간 제한을 줄여 쓸 수 있도록 연다. */
    AiServerHealthClient(ChatRelayProperties properties, ObjectMapper objectMapper,
                         Duration connectTimeout, Duration callTimeout) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.connectTimeout = connectTimeout;
        this.callTimeout = callTimeout;
        AtomicInteger seq = new AtomicInteger();
        this.deadlineExecutor = Executors.newScheduledThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable, "ai-health-deadline-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        deadlineExecutor.shutdownNow();
    }

    /**
     * AI 서버가 정상 응답하면 true. 그 밖(미설정·통신 오류·시간 초과·2xx 아님·본문 형식 이상)은 모두 false = "확인 불가".
     * 예외를 던지지 않는다.
     */
    public boolean isHealthy() {
        if (!StringUtils.hasText(properties.getApiKey()) || !StringUtils.hasText(properties.getAiBaseUrl())) {
            log.warn("[ADMIN-AI-STATUS] AI 상태 확인 불가: reason=NOT_CONFIGURED");
            return false;
        }
        HttpURLConnection connection = null;
        ScheduledFuture<?> deadline = null;
        AtomicBoolean timedOut = new AtomicBoolean();
        long deadlineNanos = System.nanoTime() + callTimeout.toNanos();
        try {
            URI uri = UriComponentsBuilder.fromUriString(properties.getAiBaseUrl()).path(HEALTH_PATH).build().toUri();
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout((int) connectTimeout.toMillis());
            connection.setReadTimeout((int) callTimeout.toMillis());
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty(API_KEY_HEADER, properties.getApiKey());
            connection.setRequestProperty("Accept", "application/json");
            HttpURLConnection target = connection;
            deadline = deadlineExecutor.schedule(() -> {
                timedOut.set(true);
                target.disconnect();
            }, callTimeout.toMillis(), TimeUnit.MILLISECONDS);

            int status = connection.getResponseCode();
            if (timedOut.get()) {
                throw new IOException("call deadline reached");
            }
            if (status < 200 || status >= 300) {
                log.warn("[ADMIN-AI-STATUS] AI 상태 확인 불가: reason=HTTP_STATUS, status={}", status);
                return false;
            }
            byte[] body;
            try (InputStream in = connection.getInputStream()) {
                body = readBounded(in, deadlineNanos, timedOut);
            }
            if (!isOk(body)) {
                log.warn("[ADMIN-AI-STATUS] AI 상태 확인 불가: reason=UNEXPECTED_BODY");
                return false;
            }
            return true;
        } catch (IOException | RuntimeException e) {
            String reason = timedOut.get() ? "TIMEOUT" : "IO_ERROR";
            // 예외 원문에는 접속 주소가 섞일 수 있어 클래스명만 남긴다
            log.warn("[ADMIN-AI-STATUS] AI 상태 확인 불가: reason={}, error={}", reason, e.getClass().getSimpleName());
            return false;
        } finally {
            if (deadline != null) {
                deadline.cancel(false);
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** AI 응답 {@code {"data": {"status": "ok"}}}만 정상으로 본다(앞단 프록시가 HTML 200을 돌려줘도 정상으로 읽지 않게). */
    private boolean isOk(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return "ok".equals(root.path("data").path("status").asText(null));
        } catch (IOException e) {
            return false;
        }
    }

    /** 마감이 지나면 EOF여도 잘린 본문으로 보고 실패시킨다(영상 중계 L-3와 같은 방식). */
    private byte[] readBounded(InputStream in, long deadlineNanos, AtomicBoolean timedOut) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4 * 1024];
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
            if (out.size() > MAX_BODY_BYTES) {
                throw new IOException("body too large");
            }
        }
        if (timedOut.get() || System.nanoTime() > deadlineNanos) {
            timedOut.set(true);
            throw new IOException("call deadline reached");
        }
        return out.toByteArray();
    }
}

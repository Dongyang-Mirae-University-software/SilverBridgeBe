package kr.silverbridge.main.domain.companion.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 말벗 AI 상태 확인(관리자 대시보드 "AI 서비스 상태", 2026-11-11).
 *
 * <p>말벗 AI({@code companion.ai-base-url})에 말벗 키로 {@code GET /companion/status}를 보낸다. AI는 이 경로도 말벗 키로
 * 막아 두었으므로(키가 없거나 틀리면 401) 200은 <b>말벗 AI 프로세스가 응답하고 우리 키를 받아들인다</b>는 뜻이다.
 * 대화(음성 인식·언어 모델) 성공까지는 보장하지 않는다. 응답 본문은 {@code success}·{@code data.enabled}만 보고 버린다.</p>
 *
 * <p>대화용 {@link AiCompanionClient}를 쓰지 않는다 - 그쪽은 실패마다 예외·WARN을 내고 제한이 90초라 대시보드 확인과 맞지 않는다.
 * 규칙은 챗봇 확인({@code AiServerHealthClient})과 같다: 키는 {@code X-API-Key} 헤더만, 리다이렉트 미추종,
 * 연결 3초·전체 5초, 마감은 전용 데몬 스레드(빈으로 등록하지 않는다 - {@code TaskScheduler} 빈이 늘면 {@code @Scheduled}가 옮겨 간다),
 * 동시 요청은 진행 중인 확인 하나를 공유, 로그는 결과 코드만·결과가 바뀔 때만 WARN/INFO. 키·주소·본문은 로그에 남기지 않는다.</p>
 *
 * <p>스위치({@code companion.enabled})는 이 클래스가 보지 않는다 - 꺼짐(DISABLED) 판정은 부르는 쪽이 한다.</p>
 */
@Slf4j
@Component
public class CompanionAiStatusClient {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String STATUS_PATH = "/companion/status";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);
    /** 상태 응답은 수백 바이트다. 앞단이 큰 HTML을 돌려줘도 붙들지 않게 작게 자른다. */
    private static final int MAX_BODY_BYTES = 16 * 1024;

    private final CompanionProperties properties;
    private final ObjectMapper objectMapper;
    private final Duration connectTimeout;
    private final Duration callTimeout;
    private final ScheduledExecutorService deadlineExecutor;

    /** 진행 중인 확인. 동시에 들어온 요청은 새로 부르지 않고 이것을 함께 기다린다. */
    private final AtomicReference<CompletableFuture<Boolean>> inFlight = new AtomicReference<>();
    /** 직전 확인 결과 코드(null = 정상). 같은 실패가 이어지면 WARN을 반복하지 않는다. */
    private final AtomicReference<String> lastFailure = new AtomicReference<>();

    @Autowired
    public CompanionAiStatusClient(CompanionProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, CONNECT_TIMEOUT, CALL_TIMEOUT);
    }

    /** 테스트가 시간 제한을 줄여 쓸 수 있도록 연다. */
    CompanionAiStatusClient(CompanionProperties properties, ObjectMapper objectMapper,
                            Duration connectTimeout, Duration callTimeout) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.connectTimeout = connectTimeout;
        this.callTimeout = callTimeout;
        AtomicInteger seq = new AtomicInteger();
        this.deadlineExecutor = Executors.newScheduledThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable, "companion-status-deadline-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        deadlineExecutor.shutdownNow();
    }

    /** 이 확인의 최대 대기 시간(공유 대기 여유 포함). 부르는 쪽이 기다림 상한을 잡을 때 쓴다. */
    public Duration maxWait() {
        return callTimeout.plusSeconds(1);
    }

    /**
     * 말벗 AI가 정상 응답하면 true. 그 밖(미설정·통신 오류·시간 초과·2xx 아님·형식 이상·{@code enabled} false)은 모두
     * false = "확인 불가". 예외를 던지지 않는다. 결과를 저장해 두지 않는다(서버 캐시 없음).
     */
    public boolean isAvailable() {
        CompletableFuture<Boolean> mine = new CompletableFuture<>();
        CompletableFuture<Boolean> running = inFlight.compareAndExchange(null, mine);
        if (running != null) {
            return awaitShared(running);
        }
        try {
            boolean ok = recordResult(probe());
            mine.complete(ok);
            return ok;
        } catch (RuntimeException e) {
            mine.complete(false);
            return false;
        } finally {
            inFlight.compareAndSet(mine, null);
        }
    }

    private boolean awaitShared(CompletableFuture<Boolean> running) {
        try {
            return running.get(maxWait().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
    }

    /** 결과 코드가 바뀔 때만 남긴다 - 실패 시작은 WARN, 회복은 INFO, 같은 실패의 반복은 DEBUG. */
    private boolean recordResult(String failure) {
        String previous = lastFailure.getAndSet(failure);
        if (failure == null) {
            if (previous != null) {
                log.info("[ADMIN-AI-STATUS] 말벗 AI 상태 확인 회복: previous={}", previous);
            }
            return true;
        }
        if (!failure.equals(previous)) {
            log.warn("[ADMIN-AI-STATUS] 말벗 AI 상태 확인 불가: reason={}", failure);
        } else {
            log.debug("[ADMIN-AI-STATUS] 말벗 AI 상태 확인 불가 지속: reason={}", failure);
        }
        return false;
    }

    /** 한 번 확인한다. 정상이면 null, 아니면 고정 결과 코드(로그에 주소·원문을 싣지 않는다). */
    private String probe() {
        if (!properties.isConfigured()) {
            return "NOT_CONFIGURED";
        }
        HttpURLConnection connection = null;
        ScheduledFuture<?> deadline = null;
        AtomicBoolean timedOut = new AtomicBoolean();
        long deadlineNanos = System.nanoTime() + callTimeout.toNanos();
        try {
            URI uri = UriComponentsBuilder.fromUriString(properties.getAiBaseUrl()).path(STATUS_PATH).build().toUri();
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
                return "HTTP_STATUS_" + status;
            }
            byte[] body;
            try (InputStream in = connection.getInputStream()) {
                body = readBounded(in, deadlineNanos, timedOut);
            }
            return evaluate(body);
        } catch (IOException | RuntimeException e) {
            // 예외 원문에는 접속 주소가 섞일 수 있어 클래스명만 남긴다
            return (timedOut.get() ? "TIMEOUT_" : "IO_ERROR_") + e.getClass().getSimpleName();
        } finally {
            if (deadline != null) {
                deadline.cancel(false);
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** {@code {"success": true, "data": {"enabled": true, ...}}}만 정상이다(앞단이 HTML 200을 돌려줘도 정상으로 읽지 않게). */
    private String evaluate(byte[] body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            return "UNEXPECTED_BODY";
        }
        if (root == null || !root.path("success").isBoolean() || !root.path("success").asBoolean()) {
            return "UNEXPECTED_BODY";
        }
        JsonNode enabled = root.path("data").path("enabled");
        if (!enabled.isBoolean()) {
            return "UNEXPECTED_BODY";
        }
        return enabled.asBoolean() ? null : "AI_DISABLED";
    }

    /** 마감이 지나면 EOF여도 잘린 본문으로 보고 실패시킨다(챗봇 확인과 같은 방식). */
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

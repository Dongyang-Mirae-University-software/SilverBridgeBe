package kr.silverbridge.main.domain.admin.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import kr.silverbridge.main.domain.chat.config.ChatRelayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
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
 * AI 서버 응답 확인(관리자 대시보드 "AI 서비스 상태", 2026-10-27).
 *
 * <p>챗 중계가 붙는 바로 그 AI 서버({@code chat.relay.ai-base-url})에 {@code GET /api/v1/live-streams}를 보낸다.
 * AI의 {@code /health}는 앞단(nginx)이 {@code /api/}만 넘겨 바깥에서 닿지 않는다(404 HTML, 2026-10-27 gosky 실측) -
 * 그래서 이미 열려 있고 키가 필요한 가벼운 조회 경로를 쓴다(챗봇과 같은 AI 프로세스, AI DB까지 거친다).
 * AI에는 챗봇 전용 상태 경로가 없어 이 확인은 <b>챗봇을 서빙하는 AI 서버가 응답하는가</b>까지만 답한다 -
 * 모델(MedGemma·Claude) 호출이 성공하는지는 보장하지 않는다. 응답 본문(세션 목록)은 성공 여부만 보고 버린다.</p>
 *
 * <p>결과는 두 가지다: 정상({@code true}) / 확인 불가({@code false}). 네트워크 오류·시간 초과·5xx·형식 이상·키 미설정을
 * 모두 "확인 불가"로 묶는다 - 우리 쪽 경로 문제와 AI 장애를 구분할 수 없어 "중단"으로 단정하지 않는다.</p>
 *
 * <p>AI HTTP 클라이언트 공통 규칙: 키는 {@code X-API-Key} 헤더만, 리다이렉트 미추종, 호출 <b>전체</b> 제한(연결 3초·전체 5초),
 * 마감은 전용 데몬 스레드에서 연결을 끊는다(빈으로 등록하지 않는다 - {@code TaskScheduler} 빈이 늘면 {@code @Scheduled}가 옮겨 간다).
 * 로그에는 결과 코드·상태 코드·예외 클래스명만 남기고, 결과가 바뀔 때만 WARN/INFO를 남긴다(장애 중 폴링마다 반복하지 않음).</p>
 */
@Slf4j
@Component
public class AiServerHealthClient {

    private static final String API_KEY_HEADER = "X-API-Key";
    /** 앞단이 넘겨 주는 키 필요 경로 중 가장 가벼운 조회. 본문은 성공 여부만 본다. */
    private static final String PROBE_PATH = "/api/v1/live-streams";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);
    /** 세션 목록은 등록 상한(3대) 기준 수 KB다. 앞단이 큰 HTML을 돌려줘도 붙들지 않게 작게 자른다. */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final ChatRelayProperties properties;
    private final ObjectMapper objectMapper;
    private final Duration connectTimeout;
    private final Duration callTimeout;
    private final ScheduledExecutorService deadlineExecutor;

    /** 생성자가 둘이라 Spring이 쓸 쪽을 명시한다 - 빠지면 기본 생성자를 찾다가 기동이 실패한다(2026-10-27 실제 발생). */
    @Autowired
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

    /** 진행 중인 확인. 동시에 들어온 요청은 새로 부르지 않고 이것을 함께 기다린다(점검 L-3). */
    private final AtomicReference<CompletableFuture<Boolean>> inFlight = new AtomicReference<>();
    /** 직전 확인 결과 코드(null = 정상). 같은 실패가 이어지면 WARN을 반복하지 않는다(점검 L-5). */
    private final AtomicReference<String> lastFailure = new AtomicReference<>();

    /**
     * AI 서버가 정상 응답하면 true. 그 밖(미설정·통신 오류·시간 초과·2xx 아님·본문 형식 이상)은 모두 false = "확인 불가".
     * 예외를 던지지 않는다.
     *
     * <p>동시에 여러 요청이 오면 <b>진행 중인 확인 하나를 함께 기다린다</b> - AI가 응답하지 않을 때 관리자 탭 수만큼 AI를
     * 두드리지 않게 한다. 기다림도 호출 제한({@code callTimeout}) + 1초 안에서 끝난다. 결과를 저장해 두지는 않는다(서버 캐시 없음).</p>
     */
    public boolean isHealthy() {
        CompletableFuture<Boolean> mine = new CompletableFuture<>();
        CompletableFuture<Boolean> running = inFlight.compareAndExchange(null, mine);
        if (running != null) {
            return awaitShared(running);
        }
        try {
            boolean healthy = recordResult(probe());
            mine.complete(healthy);
            return healthy;
        } catch (RuntimeException e) {
            mine.complete(false);
            return false;
        } finally {
            inFlight.compareAndSet(mine, null);
        }
    }

    private boolean awaitShared(CompletableFuture<Boolean> running) {
        try {
            return running.get(callTimeout.toMillis() + 1_000L, TimeUnit.MILLISECONDS);
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
                log.info("[ADMIN-AI-STATUS] AI 상태 확인 회복: previous={}", previous);
            }
            return true;
        }
        if (!failure.equals(previous)) {
            log.warn("[ADMIN-AI-STATUS] AI 상태 확인 불가: reason={}", failure);
        } else {
            log.debug("[ADMIN-AI-STATUS] AI 상태 확인 불가 지속: reason={}", failure);
        }
        return false;
    }

    /** 한 번 확인한다. 정상이면 null, 아니면 고정 결과 코드(로그·응답에 주소·원문을 싣지 않는다). */
    private String probe() {
        if (!StringUtils.hasText(properties.getApiKey()) || !StringUtils.hasText(properties.getAiBaseUrl())) {
            return "NOT_CONFIGURED";
        }
        HttpURLConnection connection = null;
        ScheduledFuture<?> deadline = null;
        AtomicBoolean timedOut = new AtomicBoolean();
        long deadlineNanos = System.nanoTime() + callTimeout.toNanos();
        try {
            URI uri = UriComponentsBuilder.fromUriString(properties.getAiBaseUrl()).path(PROBE_PATH).build().toUri();
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
                // 상태 코드는 숫자뿐이라 결과 코드에 붙여 남긴다
                return "HTTP_STATUS_" + status;
            }
            byte[] body;
            try (InputStream in = connection.getInputStream()) {
                body = readBounded(in, deadlineNanos, timedOut);
            }
            return isOk(body) ? null : "UNEXPECTED_BODY";
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

    /** AI 공통 응답 {@code {"success": true, ...}}만 정상으로 본다(앞단 프록시가 HTML 200을 돌려줘도 정상으로 읽지 않게). */
    private boolean isOk(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("success").isBoolean() && root.path("success").asBoolean();
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

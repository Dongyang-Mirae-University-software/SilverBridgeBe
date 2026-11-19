package kr.silverbridge.main.domain.game.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import kr.silverbridge.main.domain.game.config.GameRelayProperties;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 서버 게임 기록 조회 클라이언트 (보호자 게임 관리 중계, 2026-11-19).
 *
 * <p>AI 키는 {@code X-API-Key} 헤더로만 보내고 리다이렉트는 따라가지 않는다(30x로 다른 호스트를 가리키면 키 헤더가 따라간다).
 * 호출 <b>전체</b> 제한({@code callTimeout})을 넘으면 연결을 끊는다. 응답은 크기 상한 안에서만 읽는다.
 * 로그에는 호출 이름·HTTP 상태·예외 클래스명만 남긴다(예외 원문에는 접속 주소가 섞일 수 있다).
 * 모든 실패는 503 {@code GAME_UNAVAILABLE}이다.</p>
 */
@Slf4j
@Component
public class AiGameClient {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String PROGRESS_PATH = "/api/v1/games/progress";
    private static final String ACTIVITY_PATH = "/api/v1/games/activity";
    /** 게임 목록·하루 단위 활동(최대 366일)이라 작다. 메모리에 올리므로 상한을 둔다. */
    static final int MAX_JSON_BYTES = 256 * 1024;

    private final GameRelayProperties properties;
    private final ObjectMapper objectMapper;
    /**
     * 호출 마감(연결 끊기) 전용 스레드 - 영상 중계와 같은 이유로 공용 스케줄러 풀을 쓰지 않는다. 빈으로 등록하지 않는다
     * ({@code TaskScheduler} 빈이 늘면 Boot 기본 스케줄러 자동 설정이 물러난다).
     */
    private final ScheduledExecutorService deadlineExecutor;

    public AiGameClient(GameRelayProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        AtomicInteger seq = new AtomicInteger();
        this.deadlineExecutor = Executors.newScheduledThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable, "ai-game-deadline-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        deadlineExecutor.shutdownNow();
    }

    /** AI 공통 응답 {@code {success, message, data}}의 data (사용자별 게임 진행 목록). */
    public JsonNode fetchProgress(long gameUserId) {
        URI uri = UriComponentsBuilder.fromUriString(properties.getAiBaseUrl())
                .path(PROGRESS_PATH)
                .queryParam("userId", gameUserId)
                .build(true)
                .toUri();
        return getData("progress", uri);
    }

    /** AI 공통 응답의 data (날짜별 활동량). {@code days}는 호출부가 1~366으로 검증한다. */
    public JsonNode fetchActivity(long gameUserId, int days) {
        URI uri = UriComponentsBuilder.fromUriString(properties.getAiBaseUrl())
                .path(ACTIVITY_PATH)
                .queryParam("userId", gameUserId)
                .queryParam("days", days)
                .build(true)
                .toUri();
        return getData("activity", uri);
    }

    private JsonNode getData(String call, URI uri) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            log.warn("[GAME-RELAY] AI_API_KEY 미설정 - 게임 기록 중계 불가");
            throw new CustomException(ErrorCode.GAME_UNAVAILABLE);
        }
        HttpURLConnection connection = null;
        ScheduledFuture<?> deadline = null;
        AtomicBoolean timedOut = new AtomicBoolean();
        long deadlineNanos = System.nanoTime() + properties.getCallTimeout().toNanos();
        try {
            connection = open(uri);
            HttpURLConnection target = connection;
            deadline = deadlineExecutor.schedule(() -> {
                timedOut.set(true);
                target.disconnect();
            }, properties.getCallTimeout().toMillis(), TimeUnit.MILLISECONDS);
            int status = connection.getResponseCode();
            if (timedOut.get()) {
                throw new IOException("call deadline reached");
            }
            if (status < 200 || status >= 300) {
                log.warn("[GAME-RELAY] AI 응답 실패: call={}, status={}", call, status);
                throw new CustomException(ErrorCode.GAME_UNAVAILABLE);
            }
            byte[] body;
            try (InputStream in = connection.getInputStream()) {
                body = readBounded(in, deadlineNanos, timedOut, call);
            }
            JsonNode data = objectMapper.readTree(body).path("data");
            if (!data.isObject()) {
                log.warn("[GAME-RELAY] AI 응답 형식 오류: call={}", call);
                throw new CustomException(ErrorCode.GAME_UNAVAILABLE);
            }
            return data;
        } catch (IOException e) {
            if (timedOut.get()) {
                log.warn("[GAME-RELAY] AI 호출 시간 초과: call={}, limit={}", call, properties.getCallTimeout());
            } else {
                log.warn("[GAME-RELAY] AI 호출 실패: call={}, error={}", call, e.getClass().getSimpleName());
            }
            throw new CustomException(ErrorCode.GAME_UNAVAILABLE);
        } finally {
            if (deadline != null) {
                deadline.cancel(false);
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 읽을 때마다 마감·크기를 확인한다(본문을 읽는 중에는 disconnect()가 읽기를 깨우지 못한다 - 영상 중계와 같은 처리). */
    private byte[] readBounded(InputStream body, long deadlineNanos, AtomicBoolean timedOut, String call)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8 * 1024];
        while (true) {
            if (timedOut.get() || System.nanoTime() > deadlineNanos) {
                timedOut.set(true);
                throw new IOException("call deadline reached");
            }
            int read = body.read(buffer);
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            if (out.size() > MAX_JSON_BYTES) {
                log.warn("[GAME-RELAY] AI 응답이 크기 상한을 넘음: call={}, max={}", call, MAX_JSON_BYTES);
                throw new CustomException(ErrorCode.GAME_UNAVAILABLE);
            }
        }
        if (timedOut.get() || System.nanoTime() > deadlineNanos) {
            timedOut.set(true);
            throw new IOException("call deadline reached");
        }
        return out.toByteArray();
    }

    private HttpURLConnection open(URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        connection.setReadTimeout((int) properties.getReadTimeout().toMillis());
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty(API_KEY_HEADER, properties.getApiKey());
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }
}

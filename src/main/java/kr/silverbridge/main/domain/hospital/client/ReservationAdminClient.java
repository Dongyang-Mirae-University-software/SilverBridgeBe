package kr.silverbridge.main.domain.hospital.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import kr.silverbridge.main.domain.hospital.config.ReservationAdminProperties;
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
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 예약 서비스 관리자 API 클라이언트(제휴 병원 관리 중계, 2026-10-26).
 *
 * <p><b>허용 목록</b>: 로그인과 병원·진료과 관리 8개 경로만 부른다. 경로는 상수 + 숫자 ID로만 만들고 임의 경로를 받는 메서드를
 * 두지 않는다 - 같은 관리자 토큰으로 회원·API 키·예약 상세(환자 정보)까지 열 수 있으므로 메서드를 더할 때 그 경로가 정말
 * 필요한지 먼저 볼 것.</p>
 *
 * <p>인증은 BE 전용 관리자 계정으로 {@code POST /api/v1/auth/login}(email·password)해 받은 JWT를 메모리에 둔다(만료 10분 전
 * 재발급, refresh 없음). 401이 오면 토큰을 버리고 한 번만 다시 로그인해 재시도한다. 로그인이 거절되면(자격 오류) 30초 동안 다시
 * 시도하지 않는다. 비밀번호·토큰은 로그에 남기지 않는다. 예약 서비스 오류 본문은 읽지 않고 상태 코드로만 판단한다.
 * 리다이렉트는 따라가지 않는다(토큰 헤더 유출 방지).</p>
 *
 * <p><b>시간 제한</b>: {@link #withinCall}로 감싼 한 번의 관리자 요청 안에서는 목록 확인·로그인·재시도·쓰기가 마감 하나
 * ({@code callTimeout})를 함께 쓴다(2026-10-26 점검 L-2). 마감이 되면 연결을 끊는다(AI 클라이언트들과 같은 방식).</p>
 *
 * <p><b>결과 불명</b>(점검 M-1): 쓰기 요청이 예약 서비스에 닿은 뒤 시간 초과·연결 끊김·응답 형식 이상이 나면 반영됐는지 알 수 없다.
 * 이때는 {@code HOSPITAL_RESULT_UNKNOWN}으로 구분해 "새로고침해 확인"을 안내한다 - 실패로 보여 다시 누르면 중복이 생긴다.</p>
 */
@Slf4j
@Component
public class ReservationAdminClient {

    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String HOSPITALS_PATH = "/api/v1/admin/hospitals";
    private static final String DEPARTMENTS_PATH = "/api/v1/admin/departments";
    /** 토큰 만료 직전 호출이 401로 실패하지 않게 미리 재발급한다. */
    private static final Duration TOKEN_EXPIRY_MARGIN = Duration.ofMinutes(10);
    /** 토큰에서 만료 시각을 읽지 못하면 이만큼만 쓴다(예약 서비스 기본 만료는 7일). */
    private static final Duration TOKEN_FALLBACK_TTL = Duration.ofHours(1);
    /** 로그인이 거절되면 이 시간 동안 다시 시도하지 않는다(자격 오류로 요청마다 로그인이 나가는 것 방지, 점검 L-1). */
    private static final Duration LOGIN_REJECTED_BACKOFF = Duration.ofSeconds(30);
    /** 읽기 시간 제한을 마감보다 길게 둬 마감 끊기가 먼저 걸리게 한다(시간 초과가 504로 나오도록, 점검 L-3). */
    private static final long READ_TIMEOUT_SLACK_MS = 1_000;

    private final ReservationAdminProperties properties;
    private final ObjectMapper objectMapper;
    /**
     * 호출 마감(연결 끊기) 전용 스레드. 공용 스케줄러 풀(3)을 쓰면 다른 작업이 밀릴 때 마감이 늦어진다. 빈으로 등록하지 않는다 -
     * {@code TaskScheduler} 빈이 하나 더 생기면 Boot 기본 스케줄러 자동 설정이 물러나 {@code @Scheduled} 전체가 이 풀로 옮겨 간다.
     */
    private final ScheduledExecutorService deadlineExecutor;
    /** {@link #withinCall} 안의 마감(nanoTime). 밖에서 부르면 호출마다 새 마감을 쓴다. */
    private final ThreadLocal<Long> callDeadline = new ThreadLocal<>();
    private final AtomicInteger loginCount = new AtomicInteger();

    /** 로그인(토큰 갱신) 직렬화. 로그인 HTTP 호출 동안 잡고 있으므로 대기 쪽은 자기 마감까지만 기다린다(tryLock). */
    private final ReentrantLock tokenLock = new ReentrantLock();
    private volatile CachedToken cachedToken;
    private Instant loginBlockedUntil = Instant.EPOCH;

    public ReservationAdminClient(ReservationAdminProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        AtomicInteger seq = new AtomicInteger();
        this.deadlineExecutor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "reservation-admin-deadline-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        deadlineExecutor.shutdownNow();
    }

    /** 설정(주소·자격)이 비어 있으면 503. 감사 로그를 남기기 전에 먼저 부른다. */
    public void requireConfigured() {
        if (!properties.isConfigured()) {
            log.warn("[RESERVATION-ADMIN] 접속 정보 미설정 - 제휴 병원 관리 불가(RESERVATION_ADMIN_* 주입 필요)");
            throw new CustomException(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * 한 번의 관리자 요청을 감싼다 - 안에서 부르는 모든 예약 서비스 호출이 마감 하나({@code callTimeout})를 함께 쓴다.
     * 이미 감싼 안에서 다시 부르면 바깥 마감을 그대로 쓴다.
     */
    public <T> T withinCall(Supplier<T> work) {
        if (callDeadline.get() != null) {
            return work.get();
        }
        callDeadline.set(System.nanoTime() + properties.getCallTimeout().toNanos());
        try {
            return work.get();
        } finally {
            callDeadline.remove();
        }
    }

    // ── 허용 목록: 병원·진료과 관리 8개 경로 ──

    /** 삭제되지 않은 병원 전체(활성 진료과·운영 상태 포함). 예약 서비스는 검색·페이지를 지원하지 않는다. */
    public JsonNode listHospitals() {
        return send("GET", HOSPITALS_PATH, null, ErrorCode.HOSPITAL_NOT_FOUND);
    }

    public JsonNode createHospital(Map<String, Object> body) {
        return send("POST", HOSPITALS_PATH, body, ErrorCode.HOSPITAL_NOT_FOUND);
    }

    public JsonNode updateHospital(long hospitalId, Map<String, Object> body) {
        return send("PUT", HOSPITALS_PATH + "/" + hospitalId, body, ErrorCode.HOSPITAL_NOT_FOUND);
    }

    /** 소프트 삭제(예약 서비스 {@code deleted=true}) - 기존 예약 행은 남는다. */
    public JsonNode deleteHospital(long hospitalId) {
        return send("DELETE", HOSPITALS_PATH + "/" + hospitalId, null, ErrorCode.HOSPITAL_NOT_FOUND);
    }

    public JsonNode updateHospitalStatus(long hospitalId, Map<String, Object> body) {
        return send("PUT", HOSPITALS_PATH + "/" + hospitalId + "/status", body, ErrorCode.HOSPITAL_NOT_FOUND);
    }

    public JsonNode addDepartment(long hospitalId, String name) {
        return send("POST", HOSPITALS_PATH + "/" + hospitalId + "/departments", Map.of("name", name),
                ErrorCode.HOSPITAL_NOT_FOUND);
    }

    public JsonNode renameDepartment(long departmentId, String name) {
        return send("PUT", DEPARTMENTS_PATH + "/" + departmentId, Map.of("name", name),
                ErrorCode.HOSPITAL_DEPARTMENT_NOT_FOUND);
    }

    /** 예약 서비스에서 {@code active=false}(소프트 삭제). 기존 예약의 진료과는 문자열이라 영향이 없다. */
    public JsonNode deleteDepartment(long departmentId) {
        return send("DELETE", DEPARTMENTS_PATH + "/" + departmentId, null, ErrorCode.HOSPITAL_DEPARTMENT_NOT_FOUND);
    }

    /** 테스트용 - 지금까지 로그인한 횟수(토큰 캐시 확인). */
    int loginCount() {
        return loginCount.get();
    }

    // ── 내부 ──

    private JsonNode send(String method, String path, Object body, ErrorCode notFound) {
        requireConfigured();
        boolean write = !"GET".equals(method);
        Long scoped = callDeadline.get();
        long deadlineNanos = scoped != null ? scoped : System.nanoTime() + properties.getCallTimeout().toNanos();
        String token = currentToken(deadlineNanos);
        Reply reply = exchange(method, path, body, token, deadlineNanos, write);
        if (reply.status() == 401) {
            // 토큰이 만료·폐기됐다 - 한 번만 다시 로그인해 재시도한다(401은 가드에서 막힌 것이라 다시 보내도 중복 처리되지 않는다)
            invalidate(token);
            token = currentToken(deadlineNanos);
            reply = exchange(method, path, body, token, deadlineNanos, write);
        }
        if (reply.status() >= 200 && reply.status() < 300) {
            return parse(reply.body(), write);
        }
        throw mapFailure(reply.status(), notFound);
    }

    /** 예약 서비스 오류는 본문을 읽지 않고 상태 코드로만 바꾼다. */
    private CustomException mapFailure(int status, ErrorCode notFound) {
        if (status == 404) {
            return new CustomException(notFound);
        }
        if (status == 400 || status == 422) {
            log.info("[RESERVATION-ADMIN] 예약 서비스가 요청을 거절: status={}", status);
            return new CustomException(ErrorCode.HOSPITAL_REQUEST_REJECTED);
        }
        if (status == 401 || status == 403) {
            log.warn("[RESERVATION-ADMIN] 예약 서비스 관리자 인증 실패: status={} (계정 role·자격 확인 필요)", status);
            return new CustomException(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
        }
        log.warn("[RESERVATION-ADMIN] 예약 서비스 응답 실패: status={}", status);
        return new CustomException(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
    }

    private String currentToken(long deadlineNanos) {
        CachedToken cached = cachedToken;
        if (cached != null && Instant.now().isBefore(cached.refreshAt())) {
            return cached.value();
        }
        // 다른 호출이 로그인 중이면 자기 마감까지만 기다린다(synchronized는 마감을 무시하고 무한정 대기했다)
        boolean locked;
        try {
            locked = tokenLock.tryLock(Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustomException(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
        }
        if (!locked) {
            // 마감 안에 락을 못 얻음 - exchange()가 마감을 넘겼을 때와 같은 오류
            throw new CustomException(ErrorCode.HOSPITAL_SERVICE_TIMEOUT);
        }
        try {
            return loginLocked(deadlineNanos);
        } finally {
            tokenLock.unlock();
        }
    }

    private String loginLocked(long deadlineNanos) {
        // 락을 기다리는 사이 다른 호출이 갱신했을 수 있다
        CachedToken fresh = cachedToken;
        if (fresh != null && Instant.now().isBefore(fresh.refreshAt())) {
            return fresh.value();
        }
        cachedToken = null;
        if (Instant.now().isBefore(loginBlockedUntil)) {
            // 방금 자격이 거절됐다 - 같은 자격으로 다시 두드리지 않는다(WARN은 거절 시점에 한 번 남겼다)
            throw new CustomException(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
        }
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("email", properties.getEmail());
        credentials.put("password", properties.getPassword());
        Reply reply = exchange("POST", LOGIN_PATH, credentials, null, deadlineNanos, false);
        loginCount.incrementAndGet();
        if (reply.status() < 200 || reply.status() >= 300) {
            if (reply.status() == 400 || reply.status() == 401 || reply.status() == 403) {
                loginBlockedUntil = Instant.now().plus(LOGIN_REJECTED_BACKOFF);
            }
            log.warn("[RESERVATION-ADMIN] 예약 서비스 관리자 로그인 실패: status={}", reply.status());
            throw new CustomException(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
        }
        JsonNode node = parse(reply.body(), false);
        JsonNode accessToken = node.get("accessToken");
        if (accessToken == null || !accessToken.isTextual() || accessToken.asText().isBlank()) {
            log.warn("[RESERVATION-ADMIN] 로그인 응답 형식 이상(accessToken 없음)");
            throw new CustomException(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
        }
        String value = accessToken.asText();
        cachedToken = new CachedToken(value, refreshAt(value));
        loginBlockedUntil = Instant.EPOCH;
        return value;
    }

    private void invalidate(String used) {
        // 로그인 중인 호출을 기다리지 않도록 락 없이 처리 - 경합해도 최악은 다음 호출이 다시 로그인하는 것
        CachedToken cached = cachedToken;
        if (cached != null && cached.value().equals(used)) {
            cachedToken = null;
        }
    }

    /** JWT 본문의 {@code exp}에서 재발급 시각을 정한다. 서명은 확인하지 않는다(캐시 시점 계산용일 뿐, 인가는 예약 서비스가 한다). */
    private Instant refreshAt(String jwt) {
        Instant fallback = Instant.now().plus(TOKEN_FALLBACK_TTL);
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            return fallback;
        }
        try {
            JsonNode payload = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            JsonNode exp = payload == null ? null : payload.get("exp");
            if (exp == null || !exp.canConvertToLong()) {
                return fallback;
            }
            Instant refresh = Instant.ofEpochSecond(exp.asLong()).minus(TOKEN_EXPIRY_MARGIN);
            return refresh.isAfter(Instant.now()) ? refresh : Instant.now();
        } catch (IOException | IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * 요청 1회. {@code write}면 연결이 맺어진 뒤의 시간 초과·통신 오류를 "결과 불명"으로 본다 - 예약 서비스가 이미 처리했을 수 있다.
     * 연결 전 실패(주소·연결 거부·연결 시간 초과)는 요청이 닿지 않은 것이라 일반 실패다.
     */
    private Reply exchange(String method, String path, Object body, String token, long deadlineNanos, boolean write) {
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (remainingMs <= 0) {
            log.warn("[RESERVATION-ADMIN] 예약 서비스 호출 시간 초과: limit={}", properties.getCallTimeout());
            throw new CustomException(ErrorCode.HOSPITAL_SERVICE_TIMEOUT);
        }
        URI uri = UriComponentsBuilder.fromUriString(properties.getBaseUrl()).path(path).build().toUri();
        HttpURLConnection connection = null;
        ScheduledFuture<?> deadline = null;
        AtomicBoolean timedOut = new AtomicBoolean();
        boolean connected = false;
        try {
            byte[] payload = body == null ? null : objectMapper.writeValueAsBytes(body);
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout((int) Math.min(properties.getConnectTimeout().toMillis(), remainingMs));
            connection.setReadTimeout((int) (remainingMs + READ_TIMEOUT_SLACK_MS));
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (payload != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(payload.length);
            }
            HttpURLConnection target = connection;
            deadline = deadlineExecutor.schedule(() -> {
                timedOut.set(true);
                target.disconnect();
            }, remainingMs, TimeUnit.MILLISECONDS);

            connection.connect();
            connected = true;
            if (payload != null) {
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }
            }

            int status = connection.getResponseCode();
            if (timedOut.get()) {
                throw new IOException("call deadline reached");
            }
            if (status >= 200 && status < 300) {
                try (InputStream in = connection.getInputStream()) {
                    return new Reply(status, readBounded(in, deadlineNanos, timedOut, write));
                }
            }
            return new Reply(status, null);
        } catch (IOException e) {
            boolean timeout = timedOut.get() || e instanceof SocketTimeoutException;
            if (write && connected) {
                log.warn("[RESERVATION-ADMIN] 쓰기 결과 확인 불가: timeout={}, error={}", timeout, e.getClass().getSimpleName());
                throw new CustomException(ErrorCode.HOSPITAL_RESULT_UNKNOWN);
            }
            if (timeout) {
                log.warn("[RESERVATION-ADMIN] 예약 서비스 호출 시간 초과: limit={}", properties.getCallTimeout());
                throw new CustomException(ErrorCode.HOSPITAL_SERVICE_TIMEOUT);
            }
            // 예외 원문에는 접속 주소가 섞일 수 있어 클래스명만 남긴다
            log.warn("[RESERVATION-ADMIN] 예약 서비스 호출 실패: error={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
        } finally {
            if (deadline != null) {
                deadline.cancel(false);
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 2xx 본문 해석. 쓰기였다면 예약 서비스는 이미 처리했으므로 형식 이상은 실패가 아니라 결과 불명이다. */
    private JsonNode parse(byte[] raw, boolean write) {
        ErrorCode failure = write ? ErrorCode.HOSPITAL_RESULT_UNKNOWN : ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE;
        try {
            JsonNode node = raw == null || raw.length == 0 ? null : objectMapper.readTree(raw);
            if (node == null || node.isMissingNode() || node.isNull()) {
                log.warn("[RESERVATION-ADMIN] 예약 서비스 응답 형식 이상(본문 없음): write={}", write);
                throw new CustomException(failure);
            }
            return node;
        } catch (IOException e) {
            log.warn("[RESERVATION-ADMIN] 예약 서비스 응답 해석 실패: write={}, error={}", write, e.getClass().getSimpleName());
            throw new CustomException(failure);
        }
    }

    /** 덩어리마다 마감·크기를 확인하며 읽는다(마감이 지나면 EOF여도 잘린 본문으로 보고 실패시킨다). */
    private byte[] readBounded(InputStream in, long deadlineNanos, AtomicBoolean timedOut, boolean write) throws IOException {
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
            if (out.size() > properties.getMaxReplyBytes()) {
                log.warn("[RESERVATION-ADMIN] 예약 서비스 응답이 크기 상한을 넘음: max={}", properties.getMaxReplyBytes());
                throw new CustomException(write ? ErrorCode.HOSPITAL_RESULT_UNKNOWN : ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
            }
        }
        if (timedOut.get() || System.nanoTime() > deadlineNanos) {
            timedOut.set(true);
            throw new IOException("call deadline reached");
        }
        return out.toByteArray();
    }

    private record Reply(int status, byte[] body) {
    }

    private record CachedToken(String value, Instant refreshAt) {
        @Override
        public String toString() {
            return "CachedToken[refreshAt=" + refreshAt + "]"; // 토큰 값이 실수로 로그에 찍히지 않게
        }
    }
}

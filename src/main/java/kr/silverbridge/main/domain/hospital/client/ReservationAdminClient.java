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
import java.net.URI;
import java.nio.charset.StandardCharsets;
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

/**
 * 예약 서비스 관리자 API 클라이언트(제휴 병원 관리 중계, 2026-10-26).
 *
 * <p><b>허용 목록</b>: 로그인과 병원·진료과 관리 8개 경로만 부른다. 경로는 상수 + 숫자 ID로만 만들고 임의 경로를 받는 메서드를
 * 두지 않는다 - 같은 관리자 토큰으로 회원·API 키·예약 상세(환자 정보)까지 열 수 있으므로 메서드를 더할 때 그 경로가 정말
 * 필요한지 먼저 볼 것.</p>
 *
 * <p>인증은 BE 전용 관리자 계정으로 {@code POST /api/v1/auth/login}(email·password)해 받은 JWT를 메모리에 둔다(만료 10분 전
 * 재발급, refresh 없음). 401이 오면 토큰을 버리고 한 번만 다시 로그인해 재시도한다. 비밀번호·토큰은 로그에 남기지 않는다.
 * 예약 서비스 오류 본문은 읽지 않고 상태 코드로만 판단한다. 리다이렉트는 따라가지 않고(토큰 헤더 유출 방지), 한 요청의
 * 로그인·재시도까지 합쳐 호출 전체 시간 제한을 둔다 - 마감이 되면 연결을 끊는다(AI 클라이언트들과 같은 방식).</p>
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

    private final ReservationAdminProperties properties;
    private final ObjectMapper objectMapper;
    /**
     * 호출 마감(연결 끊기) 전용 스레드. 공용 스케줄러 풀(3)을 쓰면 다른 작업이 밀릴 때 마감이 늦어진다. 빈으로 등록하지 않는다 -
     * {@code TaskScheduler} 빈이 하나 더 생기면 Boot 기본 스케줄러 자동 설정이 물러나 {@code @Scheduled} 전체가 이 풀로 옮겨 간다.
     */
    private final ScheduledExecutorService deadlineExecutor;
    private final AtomicInteger loginCount = new AtomicInteger();

    private CachedToken cachedToken;

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
        long deadlineNanos = System.nanoTime() + properties.getCallTimeout().toNanos();
        String token = currentToken(deadlineNanos);
        Reply reply = exchange(method, path, body, token, deadlineNanos);
        if (reply.status() == 401) {
            // 토큰이 만료·폐기됐다 - 한 번만 다시 로그인해 재시도한다(401은 가드에서 막힌 것이라 다시 보내도 중복 처리되지 않는다)
            invalidate(token);
            token = currentToken(deadlineNanos);
            reply = exchange(method, path, body, token, deadlineNanos);
        }
        if (reply.status() >= 200 && reply.status() < 300) {
            return parse(reply.body());
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

    private synchronized String currentToken(long deadlineNanos) {
        if (cachedToken != null && Instant.now().isBefore(cachedToken.refreshAt())) {
            return cachedToken.value();
        }
        cachedToken = null;
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("email", properties.getEmail());
        credentials.put("password", properties.getPassword());
        Reply reply = exchange("POST", LOGIN_PATH, credentials, null, deadlineNanos);
        loginCount.incrementAndGet();
        if (reply.status() < 200 || reply.status() >= 300) {
            log.warn("[RESERVATION-ADMIN] 예약 서비스 관리자 로그인 실패: status={}", reply.status());
            throw new CustomException(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
        }
        JsonNode node = parse(reply.body());
        JsonNode accessToken = node.get("accessToken");
        if (accessToken == null || !accessToken.isTextual() || accessToken.asText().isBlank()) {
            log.warn("[RESERVATION-ADMIN] 로그인 응답 형식 이상(accessToken 없음)");
            throw new CustomException(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
        }
        String value = accessToken.asText();
        cachedToken = new CachedToken(value, refreshAt(value));
        return value;
    }

    private synchronized void invalidate(String used) {
        if (cachedToken != null && cachedToken.value().equals(used)) {
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

    private Reply exchange(String method, String path, Object body, String token, long deadlineNanos) {
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (remainingMs <= 0) {
            log.warn("[RESERVATION-ADMIN] 예약 서비스 호출 시간 초과: limit={}", properties.getCallTimeout());
            throw new CustomException(ErrorCode.HOSPITAL_SERVICE_TIMEOUT);
        }
        URI uri = UriComponentsBuilder.fromUriString(properties.getBaseUrl()).path(path).build().toUri();
        HttpURLConnection connection = null;
        ScheduledFuture<?> deadline = null;
        AtomicBoolean timedOut = new AtomicBoolean();
        try {
            byte[] payload = body == null ? null : objectMapper.writeValueAsBytes(body);
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout((int) Math.min(properties.getConnectTimeout().toMillis(), remainingMs));
            connection.setReadTimeout((int) remainingMs);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            HttpURLConnection target = connection;
            deadline = deadlineExecutor.schedule(() -> {
                timedOut.set(true);
                target.disconnect();
            }, remainingMs, TimeUnit.MILLISECONDS);

            if (payload != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(payload.length);
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
                    return new Reply(status, readBounded(in, deadlineNanos, timedOut));
                }
            }
            return new Reply(status, null);
        } catch (IOException e) {
            if (timedOut.get()) {
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

    private JsonNode parse(byte[] raw) {
        try {
            JsonNode node = raw == null ? null : objectMapper.readTree(raw);
            if (node == null || node.isMissingNode() || node.isNull()) {
                log.warn("[RESERVATION-ADMIN] 예약 서비스 응답 형식 이상(본문 없음)");
                throw new CustomException(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
            }
            return node;
        } catch (IOException e) {
            log.warn("[RESERVATION-ADMIN] 예약 서비스 응답 해석 실패: error={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
        }
    }

    /** 덩어리마다 마감·크기를 확인하며 읽는다(마감이 지나면 EOF여도 잘린 본문으로 보고 실패시킨다). */
    private byte[] readBounded(InputStream in, long deadlineNanos, AtomicBoolean timedOut) throws IOException {
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
                throw new CustomException(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
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

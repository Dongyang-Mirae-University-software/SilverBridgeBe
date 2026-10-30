package kr.silverbridge.main.domain.hospital.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kr.silverbridge.main.domain.hospital.config.ReservationAdminProperties;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 예약 서비스 관리자 클라이언트 - 실제 HTTP(로컬 임시 서버)로 로그인·토큰 캐시·401 재로그인·오류 매핑·시간 제한·
 * 리다이렉트 미추종·허용 목록 경로·결과 불명 구분·호출 마감 공유를 확인한다.
 */
class ReservationAdminClientTest {

    private static final String EMAIL = "be-admin@silverbridge.internal";
    private static final String PASSWORD = "test-password-123";

    private HttpServer server;
    private ReservationAdminProperties properties;
    private ReservationAdminClient client;

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger logins = new AtomicInteger();
    private final AtomicReference<String> issuedToken = new AtomicReference<>();
    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();
    private final AtomicReference<String> receivedLoginBody = new AtomicReference<>();
    private final AtomicInteger loginStatus = new AtomicInteger(201);
    private final AtomicReference<String> loginBody = new AtomicReference<>();
    private final AtomicReference<Long> loginDelayMs = new AtomicReference<>(0L);
    private final AtomicInteger adminStatus = new AtomicInteger(200);
    /** null이면 경로별 기본 본문. 빈 문자열이면 본문 없음. */
    private final AtomicReference<String> adminBody = new AtomicReference<>();
    /** 이 횟수만큼은 관리자 경로가 401을 준다(토큰 만료 흉내). */
    private final AtomicInteger unauthorizedTimes = new AtomicInteger();
    private final AtomicReference<Long> delayMs = new AtomicReference<>(0L);
    private final AtomicBoolean redirectFollowed = new AtomicBoolean();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        properties = new ReservationAdminProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setEmail(EMAIL);
        properties.setPassword(PASSWORD);
        client = new ReservationAdminClient(properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        client.shutdown();
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        requests.add(ex.getRequestMethod() + " " + path);
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path.equals("/elsewhere")) {
            redirectFollowed.set(true);
            json(ex, 200, "[]");
            return;
        }
        if (path.equals("/api/v1/auth/login")) {
            logins.incrementAndGet();
            receivedLoginBody.set(body);
            sleep(loginDelayMs.get());
            String token = jwt(Instant.now().plus(Duration.ofDays(7))) + "." + logins.get();
            issuedToken.set(token);
            String reply = loginBody.get() != null ? loginBody.get()
                    : "{\"accessToken\":\"" + token + "\",\"user\":{\"id\":9,\"role\":\"ADMIN\"}}";
            json(ex, loginStatus.get(), reply);
            return;
        }
        receivedAuthorization.set(ex.getRequestHeaders().getFirst("Authorization"));
        sleep(delayMs.get());
        if (unauthorizedTimes.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            json(ex, 401, "{\"success\":false,\"code\":\"UNAUTHORIZED\",\"message\":\"secret detail\"}");
            return;
        }
        if (adminStatus.get() == 302) {
            ex.getResponseHeaders().set("Location", "/elsewhere");
            ex.sendResponseHeaders(302, -1);
            ex.close();
            return;
        }
        if (adminStatus.get() != 200) {
            json(ex, adminStatus.get(), "{\"success\":false,\"code\":\"HTTP_ERROR\",\"message\":\"internal detail\"}");
            return;
        }
        if (adminBody.get() != null) {
            json(ex, 200, adminBody.get());
        } else if (path.equals("/api/v1/admin/hospitals") && ex.getRequestMethod().equals("GET")) {
            json(ex, 200, "[{\"id\":1,\"name\":\"가\"}]");
        } else {
            json(ex, 200, "{\"id\":1}");
        }
    }

    /** exp만 담은 서명 없는 JWT 앞 두 조각(클라이언트는 서명을 확인하지 않는다). */
    private static String jwt(Instant exp) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + enc.encodeToString(("{\"sub\":9,\"exp\":" + exp.getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ErrorCode codeOf(ThrowingSupplier<?> call) {
        try {
            call.get();
        } catch (CustomException e) {
            return e.getErrorCode();
        } catch (Throwable t) {
            throw new AssertionError("CustomException이 아닌 예외: " + t, t);
        }
        throw new AssertionError("예외가 나지 않았다");
    }

    @Test
    @DisplayName("관리자 계정으로 한 번 로그인하고 받은 토큰을 Bearer로 붙이며, 다음 호출은 토큰을 재사용한다")
    void login_once_and_reuse_token() {
        JsonNode first = client.listHospitals();
        client.listHospitals();

        assertThat(first.isArray()).isTrue();
        assertThat(logins.get()).isEqualTo(1);
        assertThat(receivedAuthorization.get()).isEqualTo("Bearer " + issuedToken.get());
        assertThat(receivedLoginBody.get()).contains("\"email\":\"" + EMAIL + "\"").contains(PASSWORD);
    }

    @Test
    @DisplayName("다른 호출이 로그인 중이면 자기 마감까지만 기다리고 504 HOSPITAL_SERVICE_TIMEOUT (L-8)")
    void waiting_for_login_lock_respects_own_deadline() throws Exception {
        loginDelayMs.set(1_500L);
        properties.setCallTimeout(Duration.ofSeconds(5));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<JsonNode> first = pool.submit(() -> client.listHospitals());
            long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (logins.get() < 1 && System.nanoTime() < waitUntil) {
                Thread.sleep(10);
            }
            assertThat(logins.get()).isEqualTo(1);
            properties.setCallTimeout(Duration.ofMillis(300));

            long started = System.nanoTime();
            assertThat(codeOf(() -> client.listHospitals())).isEqualTo(ErrorCode.HOSPITAL_SERVICE_TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_000));
            // 앞선 로그인은 그대로 끝난다
            assertThat(first.get(5, TimeUnit.SECONDS).isArray()).isTrue();
            assertThat(logins.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("동시에 여러 요청이 와도 로그인은 한 번만 나간다")
    void concurrent_requests_login_once() throws Exception {
        loginDelayMs.set(200L);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<JsonNode>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return client.listHospitals();
            }));
        }
        start.countDown();
        for (Future<JsonNode> future : futures) {
            assertThat(future.get(5, TimeUnit.SECONDS).isArray()).isTrue();
        }
        pool.shutdownNow();

        assertThat(logins.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("관리자 경로가 401이면 토큰을 버리고 한 번만 다시 로그인해 재시도한다")
    void relogin_once_on_401() {
        client.listHospitals();
        unauthorizedTimes.set(1);

        JsonNode reply = client.listHospitals();

        assertThat(reply.isArray()).isTrue();
        assertThat(logins.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("재로그인 뒤에도 401이면 더 시도하지 않고 503")
    void second_401_gives_503() {
        unauthorizedTimes.set(5);

        assertThat(codeOf(() -> client.listHospitals())).isEqualTo(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
        assertThat(logins.get()).isEqualTo(2);
        assertThat(requests).filteredOn(r -> r.startsWith("GET /api/v1/admin/hospitals")).hasSize(2);
    }

    @Test
    @DisplayName("로그인이 거절되면(자격 오류) 503이고, 30초 동안은 다시 로그인하지 않는다")
    void login_rejected_is_remembered() {
        loginStatus.set(401);

        assertThat(codeOf(() -> client.listHospitals())).isEqualTo(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
        assertThat(codeOf(() -> client.listHospitals())).isEqualTo(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);

        assertThat(requests).containsExactly("POST /api/v1/auth/login");
    }

    @Test
    @DisplayName("로그인 응답에 accessToken이 없으면 503이고 관리자 경로를 부르지 않는다")
    void login_without_token() {
        loginBody.set("{\"user\":{\"id\":9}}");

        assertThat(codeOf(() -> client.listHospitals())).isEqualTo(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
        assertThat(requests).containsExactly("POST /api/v1/auth/login");
    }

    @Test
    @DisplayName("접속 정보가 비어 있으면 HTTP 호출 없이 503")
    void not_configured_gives_503_without_call() {
        properties.setPassword("");

        assertThat(codeOf(() -> client.listHospitals())).isEqualTo(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
        assertThat(requests).isEmpty();
    }

    @Test
    @DisplayName("404는 경로에 맞는 없음 오류로 바꾼다 - 병원은 HOSPITAL_NOT_FOUND, 진료과는 HOSPITAL_DEPARTMENT_NOT_FOUND")
    void not_found_mapping() {
        adminStatus.set(404);

        assertThat(codeOf(() -> client.deleteHospital(7))).isEqualTo(ErrorCode.HOSPITAL_NOT_FOUND);
        assertThat(codeOf(() -> client.deleteDepartment(31))).isEqualTo(ErrorCode.HOSPITAL_DEPARTMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("400은 HOSPITAL_REQUEST_REJECTED, 5xx는 503(쓰기여도 결과 불명이 아님)이며 예약 서비스 오류 문구는 옮기지 않는다")
    void client_and_server_error_mapping() {
        adminStatus.set(400);
        assertThatThrownBy(() -> client.updateHospital(1, Map.of("name", "x")))
                .isInstanceOf(CustomException.class)
                .hasMessageNotContaining("internal detail")
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.HOSPITAL_REQUEST_REJECTED);

        adminStatus.set(500);
        assertThatThrownBy(() -> client.updateHospital(1, Map.of("name", "x")))
                .hasMessageNotContaining("internal detail")
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("조회가 시간 제한을 넘기면 연결을 끊고 504 HOSPITAL_SERVICE_TIMEOUT")
    void read_timeout_gives_504() {
        properties.setCallTimeout(Duration.ofMillis(400));
        delayMs.set(2_000L);

        long started = System.nanoTime();
        assertThat(codeOf(() -> client.listHospitals())).isEqualTo(ErrorCode.HOSPITAL_SERVICE_TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_800));
    }

    @Test
    @DisplayName("쓰기가 예약 서비스에 닿은 뒤 시간 제한을 넘기면 실패가 아니라 결과 불명(HOSPITAL_RESULT_UNKNOWN)")
    void write_timeout_is_result_unknown() {
        client.listHospitals(); // 토큰 먼저 받아 둔다
        properties.setCallTimeout(Duration.ofMillis(400));
        delayMs.set(2_000L);

        assertThat(codeOf(() -> client.createHospital(Map.of("name", "가")))).isEqualTo(ErrorCode.HOSPITAL_RESULT_UNKNOWN);
        assertThat(codeOf(() -> client.deleteHospital(3))).isEqualTo(ErrorCode.HOSPITAL_RESULT_UNKNOWN);
    }

    @Test
    @DisplayName("2xx인데 본문이 비거나 깨졌으면: 쓰기는 결과 불명, 조회는 503")
    void empty_or_broken_success_body() {
        adminBody.set("");
        assertThat(codeOf(() -> client.updateHospital(1, Map.of("name", "x")))).isEqualTo(ErrorCode.HOSPITAL_RESULT_UNKNOWN);
        assertThat(codeOf(() -> client.listHospitals())).isEqualTo(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);

        adminBody.set("{broken");
        assertThat(codeOf(() -> client.addDepartment(1, "내과"))).isEqualTo(ErrorCode.HOSPITAL_RESULT_UNKNOWN);
    }

    @Test
    @DisplayName("응답이 크기 상한을 넘으면 조회는 503")
    void oversize_reply() {
        properties.setMaxReplyBytes(64);
        adminBody.set("[" + "{\"id\":1,\"name\":\"가나다라마바사\"},".repeat(10) + "{}]");

        assertThat(codeOf(() -> client.listHospitals())).isEqualTo(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("연결 자체가 안 되면 쓰기여도 결과 불명이 아니라 503(요청이 닿지 않았다)")
    void connection_refused_write_is_unavailable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        properties.setBaseUrl("http://127.0.0.1:" + closedPort);

        assertThat(codeOf(() -> client.createHospital(Map.of("name", "가")))).isEqualTo(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("withinCall 안에서는 여러 호출이 마감 하나를 함께 쓴다 - 밖에서는 호출마다 새 마감")
    void within_call_shares_deadline() {
        client.listHospitals(); // 토큰 먼저
        properties.setCallTimeout(Duration.ofMillis(700));
        delayMs.set(400L);

        // 밖: 400ms짜리 두 번은 각자 700ms 안
        client.listHospitals();
        client.listHospitals();

        // 안: 합쳐 800ms라 두 번째가 마감을 넘긴다
        assertThat(codeOf(() -> client.withinCall(() -> {
            client.listHospitals();
            return client.listHospitals();
        }))).isEqualTo(ErrorCode.HOSPITAL_SERVICE_TIMEOUT);
    }

    @Test
    @DisplayName("리다이렉트를 따라가지 않는다(토큰 헤더 유출 방지) - 3xx는 503")
    void redirect_not_followed() {
        adminStatus.set(302);

        assertThat(codeOf(() -> client.listHospitals())).isEqualTo(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
        assertThat(redirectFollowed).isFalse();
    }

    @Test
    @DisplayName("허용 목록: 8개 기능이 부르는 경로는 로그인과 병원·진료과 관리 경로뿐이다")
    void only_allowlisted_paths() {
        client.listHospitals();
        client.createHospital(Map.of("name", "가"));
        client.updateHospital(3, Map.of("name", "나"));
        client.deleteHospital(3);
        client.updateHospitalStatus(3, Map.of("notice", ""));
        client.addDepartment(3, "내과");
        client.renameDepartment(31, "외과");
        client.deleteDepartment(31);

        assertThat(requests).allSatisfy(r -> assertThat(r).matches(
                "(POST /api/v1/auth/login"
                        + "|(GET|POST) /api/v1/admin/hospitals"
                        + "|(PUT|DELETE) /api/v1/admin/hospitals/\\d+"
                        + "|PUT /api/v1/admin/hospitals/\\d+/status"
                        + "|POST /api/v1/admin/hospitals/\\d+/departments"
                        + "|(PUT|DELETE) /api/v1/admin/departments/\\d+)"));
        assertThat(requests).noneMatch(r -> r.contains("/admin/users") || r.contains("/admin/reservations")
                || r.contains("api-key") || r.contains("admin-login"));
    }
}

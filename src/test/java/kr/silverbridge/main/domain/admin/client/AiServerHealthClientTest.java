package kr.silverbridge.main.domain.admin.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kr.silverbridge.main.domain.chat.config.ChatRelayProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 서버 응답 확인(관리자 대시보드 AI 서비스 상태, 확인 경로 GET /api/v1/live-streams). 고정하는 것은 <b>"정상이라고 확인한 경우에만 true"</b>다 -
 * 시간 초과·5xx·형식 이상·설정 없음을 정상으로 읽으면 장애가 화면에서 "정상"으로 보인다.
 */
class AiServerHealthClientTest {

    private static final String OK_BODY =
            "{\"success\":true,\"message\":\"라이브 세션 목록 조회 완료\",\"data\":[{\"sessionId\":\"ward_a1B2c3D4e5F6g7H8\"}]}";

    private HttpServer server;
    private final AtomicReference<Handler> handler = new AtomicReference<>();
    private final AtomicReference<String> receivedKey = new AtomicReference<>();
    private final AtomicInteger redirectTargetHits = new AtomicInteger();
    private final AtomicInteger probeHits = new AtomicInteger();

    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/live-streams", exchange -> {
            probeHits.incrementAndGet();
            receivedKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            handler.get().handle(exchange);
        });
        server.createContext("/elsewhere", exchange -> {
            redirectTargetHits.incrementAndGet();
            respond(exchange, 200, OK_BODY);
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private AiServerHealthClient client(String apiKey, Duration callTimeout) {
        ChatRelayProperties properties = new ChatRelayProperties();
        properties.setAiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey(apiKey);
        return new AiServerHealthClient(properties, new ObjectMapper(), Duration.ofSeconds(1), callTimeout);
    }

    @Test
    @DisplayName("정상: success=true면 true, 키는 X-API-Key 헤더로만 간다")
    void ok() {
        handler.set(exchange -> respond(exchange, 200, OK_BODY));

        assertThat(client("test-key", Duration.ofSeconds(2)).isHealthy()).isTrue();
        assertThat(receivedKey.get()).isEqualTo("test-key");
    }

    @Test
    @DisplayName("5xx면 false(확인 불가)")
    void serverError() {
        handler.set(exchange -> respond(exchange, 503, "{}"));

        assertThat(client("test-key", Duration.ofSeconds(2)).isHealthy()).isFalse();
    }

    @Test
    @DisplayName("앞단이 HTML 200을 돌려줘도 정상으로 읽지 않는다")
    void htmlOkIsNotHealthy() {
        handler.set(exchange -> respond(exchange, 200, "<html>gateway</html>"));

        assertThat(client("test-key", Duration.ofSeconds(2)).isHealthy()).isFalse();
    }

    @Test
    @DisplayName("success가 true가 아니면 false")
    void notOkStatus() {
        handler.set(exchange -> respond(exchange, 200, "{\"success\":false,\"errorCode\":\"X\"}"));

        assertThat(client("test-key", Duration.ofSeconds(2)).isHealthy()).isFalse();
    }

    @Test
    @DisplayName("시간 초과: 응답이 마감보다 늦으면 마감 근처에서 false로 끝난다")
    void timeout() {
        handler.set(exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, OK_BODY);
        });

        long started = System.nanoTime();
        boolean healthy = client("test-key", Duration.ofMillis(500)).isHealthy();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(healthy).isFalse();
        assertThat(elapsedMillis).isLessThan(2_000);
    }

    @Test
    @DisplayName("리다이렉트는 따라가지 않는다(키 헤더 유출 방지) - 3xx는 false")
    void redirectNotFollowed() {
        handler.set(exchange -> {
            exchange.getResponseHeaders().add("Location",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        assertThat(client("test-key", Duration.ofSeconds(2)).isHealthy()).isFalse();
        assertThat(redirectTargetHits.get()).isZero();
    }

    @Test
    @DisplayName("키가 없으면 AI를 부르지 않고 false")
    void noKey() {
        handler.set(exchange -> respond(exchange, 200, OK_BODY));

        assertThat(client("", Duration.ofSeconds(2)).isHealthy()).isFalse();
        assertThat(receivedKey.get()).isNull();
    }

    @Test
    @DisplayName("접속이 안 되면(닫힌 포트) false - 예외를 던지지 않는다")
    void connectionRefused() {
        AiServerHealthClient client = client("test-key", Duration.ofSeconds(2));
        server.stop(0);

        assertThat(client.isHealthy()).isFalse();
    }

    @Test
    @DisplayName("동시에 들어온 확인은 진행 중인 한 번을 함께 기다린다 - AI 호출은 1회(점검 L-3)")
    void concurrentChecksShareOneCall() throws Exception {
        handler.set(exchange -> {
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, OK_BODY);
        });
        AiServerHealthClient client = client("test-key", Duration.ofSeconds(3));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return client.isHealthy();
                }));
            }
            start.countDown();
            for (Future<Boolean> result : results) {
                assertThat(result.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(probeHits.get()).isEqualTo(1);
        // 끝난 뒤에는 다시 새로 확인한다(결과를 저장해 두지 않는다)
        assertThat(client.isHealthy()).isTrue();
        assertThat(probeHits.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("WARN은 실패가 시작될 때 한 번만, 회복되면 INFO - 장애 중 폴링마다 반복하지 않는다(점검 L-5)")
    void logsOnlyOnTransition() {
        Logger logger = (Logger) LoggerFactory.getLogger(AiServerHealthClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            AiServerHealthClient client = client("test-key", Duration.ofSeconds(2));
            handler.set(exchange -> respond(exchange, 503, "{}"));
            client.isHealthy();
            client.isHealthy();
            client.isHealthy();
            handler.set(exchange -> respond(exchange, 200, OK_BODY));
            client.isHealthy();
            client.isHealthy();

            assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.WARN).hasSize(1)
                    .first().extracting(ILoggingEvent::getFormattedMessage).asString().contains("HTTP_STATUS_503");
            assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.INFO).hasSize(1);
        } finally {
            logger.detachAppender(appender);
        }
    }
}

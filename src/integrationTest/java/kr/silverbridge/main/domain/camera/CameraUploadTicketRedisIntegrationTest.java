package kr.silverbridge.main.domain.camera;

import kr.silverbridge.main.domain.camera.config.CameraStreamProperties;
import kr.silverbridge.main.domain.camera.config.CameraUploadProperties;
import kr.silverbridge.main.domain.camera.service.CameraStreamTicketService;
import kr.silverbridge.main.domain.camera.service.CameraUploadTicketService;
import kr.silverbridge.main.domain.camera.service.CameraUploadTicketService.UploadTicket;
import kr.silverbridge.main.global.exception.CustomException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 송출 업로드 티켓의 1회 소비(GETDEL)를 <b>실제 Redis</b>에서 검증한다 - 같은 티켓을 동시에 여러 연결이 내밀어도
 * 정확히 한 건만 통과해야 한다(조회와 삭제를 나누면 둘 이상이 열린다). 운영과 같은 {@code redis:7.2}, Spring 컨텍스트 없이
 * Lettuce만 만든다({@code SmsFallbackLimiterRedisIntegrationTest}와 같은 방식). vkcs에서 실행한다.
 */
class CameraUploadTicketRedisIntegrationTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2").withExposedPorts(6379);
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;

    private CameraUploadTicketService uploadTickets;

    @BeforeAll
    static void start() {
        REDIS.start();
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void stop() {
        factory.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        template.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        uploadTickets = new CameraUploadTicketService(template, new CameraUploadProperties());
    }

    @Test
    @DisplayName("같은 티켓을 20개 연결이 동시에 소비해도 정확히 1건만 통과한다")
    void 동시_소비_한_건() throws Exception {
        String ticket = uploadTickets.issue("WD0001", "ward_race0001");
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Boolean> task = () -> {
                ready.countDown();
                go.await();
                try {
                    UploadTicket consumed = uploadTickets.consume(ticket);
                    return consumed.sessionId().equals("ward_race0001");
                } catch (CustomException e) {
                    return false;
                }
            };
            results.add(pool.submit(task));
        }
        ready.await();
        go.countDown();
        int success = 0;
        for (Future<Boolean> result : results) {
            if (result.get(10, TimeUnit.SECONDS)) {
                success++;
            }
        }
        pool.shutdownNow();

        assertThat(success).as("동시 소비 성공 수").isEqualTo(1);
    }

    @Test
    @DisplayName("티켓에는 TTL이 걸리고, 시청 티켓 서비스로는 소비되지 않으며 소비하지 못한 티켓은 그대로 남는다")
    void TTL과_키_공간_분리() {
        String ticket = uploadTickets.issue("WD0001", "ward_ttl00001");
        Long ttl = template.getExpire("camera:upload:ticket:" + ticket);
        assertThat(ttl).isBetween(1L, 60L);

        CameraStreamTicketService streamTickets = new CameraStreamTicketService(template, new CameraStreamProperties());
        org.junit.jupiter.api.Assertions.assertThrows(CustomException.class,
                () -> streamTickets.consume(ticket, "ward_ttl00001"));
        // 시청 쪽 거절은 Redis를 건드리지 않았으므로 송출 티켓은 여전히 한 번 소비할 수 있다
        assertThat(uploadTickets.consume(ticket).wardId()).isEqualTo("WD0001");
    }
}

package kr.silverbridge.main.domain.auth;

import kr.silverbridge.main.domain.auth.dto.LoginRequest;
import kr.silverbridge.main.domain.auth.service.AuthService;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.support.FullContextIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시 로그인이 DB 커넥션 풀을 멈추지 않는가 (2026-11-10 전체 점검 2차 H-1).
 *
 * <p>로그인은 트랜잭션으로 커넥션 1개를 쥔 채 BCrypt 비교까지 돈다. 접속 로그를 그 안에서 REQUIRES_NEW로 쓰면 요청마다
 * 두 번째 커넥션이 필요해, 동시 요청 수가 풀 크기(10)에 닿는 순간 전원이 두 번째를 기다리며 아무도 반납하지 못한다.
 * 풀 대기 시간(10초)이 지나 접속 로그가 포기할 때까지 로그인은 물론 DB를 쓰는 모든 요청(SOS 포함)이 멈춘다.</p>
 *
 * <p>풀보다 많은 사용자가 같은 순간 로그인해도 대기 시간보다 훨씬 빨리 끝나야 하고, 접속 로그는 빠짐없이 남아야 한다.
 * 한 계정으로 몰면 로그인 시도 예약(계정당 5회)에 걸리므로 사용자를 나눈다. 목으로는 풀 고갈을 볼 수 없어 실 DB가 필요하다.</p>
 */
class ConcurrentLoginConnectionPoolFullIntegrationTest extends FullContextIntegrationTest {

    /** 풀 크기(기본 10)보다 많아야 한다. */
    private static final int USERS = 16;
    /** 풀 대기 시간(10초)보다 짧아야 한다 - 풀이 멈추면 이 안에 끝나지 못한다. */
    private static final long LIMIT_SECONDS = 8;

    @Autowired private AuthService authService;

    @Test
    @DisplayName("풀 크기보다 많은 동시 로그인이 풀 대기 시간 안에 모두 끝나고 접속 로그가 전부 남는다")
    void 동시_로그인이_커넥션_풀을_멈추지_않는다() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < USERS; i++) {
            String id = String.format("GL%02d", i);
            saveUser(id, Role.GUARDIAN, Status.ACTIVE);
            ids.add(id);
        }

        ExecutorService pool = Executors.newFixedThreadPool(USERS);
        CountDownLatch ready = new CountDownLatch(USERS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> logins = new ArrayList<>();
            for (String id : ids) {
                logins.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    authService.login(loginRequest(id), "127.0.0.1", "it-agent");
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            long began = System.nanoTime();
            start.countDown();
            for (Future<?> login : logins) {
                long left = TimeUnit.SECONDS.toNanos(LIMIT_SECONDS) - (System.nanoTime() - began);
                // 풀이 멈추면 여기서 TimeoutException으로 실패한다
                login.get(Math.max(left, 1), TimeUnit.NANOSECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("SELECT count(*) FROM refresh_token")).as("전원 로그인 성공").isEqualTo(USERS);
        // 접속 로그는 트랜잭션이 끝난 뒤 전용 풀에서 쓴다 - 늦게라도 전부 남아야 한다
        eventually().untilAsserted(() ->
                assertThat(count("SELECT count(*) FROM access_log WHERE action = 'LOGIN'")).isEqualTo(USERS));
    }

    private static LoginRequest loginRequest(String userId) {
        LoginRequest request = new LoginRequest();
        ReflectionTestUtils.setField(request, "email", userId.toLowerCase() + "@test.local");
        ReflectionTestUtils.setField(request, "password", PASSWORD);
        return request;
    }
}

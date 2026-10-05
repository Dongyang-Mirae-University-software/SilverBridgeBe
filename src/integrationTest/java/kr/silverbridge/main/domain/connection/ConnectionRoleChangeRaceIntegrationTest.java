package kr.silverbridge.main.domain.connection;

import kr.silverbridge.main.domain.connection.dto.ConnectionRequestDto;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionRequestLimiter;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 연결 요청과 관리자 역할 변경이 겹쳐도 "보호자가 된 사용자 앞으로 PENDING"이 남지 않는가 (CONN-G15, 2026-10-05).
 *
 * <p>두 경로는 같은 {@code users} 행을 먼저 잠근다 - 연결 생성은 {@code FOR SHARE}
 * ({@link UserRepository#lockForConnectionChange}), 회원 정보 수정은 {@code FOR NO KEY UPDATE}
 * ({@link UserRepository#lockForAccountChange}). 잠금이 없으면 역할 변경의 정리 조회가 미커밋 요청을 못 보고 지나간다.
 * 어느 쪽이 먼저 잠그든 결과가 맞는지 두 순서를 모두 실제 DB에서 확인한다.</p>
 *
 * <p>역할 변경 쪽은 {@code AdminUserService.updateUser}가 하는 일(잠금 → 역할 변경 → 연결 정리)을 같은 트랜잭션에서
 * 그대로 재현한다(카메라·복약·감사 로그는 이 경합과 무관해 뺐다). 테스트 트랜잭션을 끄고 실제로 커밋한다.</p>
 */
@Import(ConnectionService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConnectionRoleChangeRaceIntegrationTest extends PostgresIntegrationTest {

    private static final String GUARDIAN_ID = "GRR001";
    private static final String WARD_ID = "WRR001";

    /** 잠금 대기 중임을 확인하는 시간. 잠금이 없으면 이 안에 끝나 버린다. */
    private static final long BLOCK_CHECK_MILLIS = 700;

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ConnectionService connectionService;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private UserRepository userRepository;

    // 요청 횟수 제한(Redis)은 이 경합의 관심사가 아니다
    @MockitoBean private ConnectionRequestLimiter connectionRequestLimiter;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user(GUARDIAN_ID, "보호자", Role.GUARDIAN));
        userRepository.save(TestData.user(WARD_ID, "피보호자", Role.WARD));
    }

    @AfterEach
    void tearDown() {
        // 연결은 회원 삭제의 CASCADE로 함께 지워진다
        userRepository.deleteAllById(List.of(GUARDIAN_ID, WARD_ID));
    }

    @Test
    @DisplayName("역할 변경이 먼저 잠그면 연결 요청은 커밋을 기다렸다가 바뀐 역할을 읽고 거절된다")
    void 역할변경이_먼저면_요청이_거절된다() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> roleChange = CompletableFuture.runAsync(() ->
                tx().executeWithoutResult(status -> {
                    userRepository.lockForAccountChange(WARD_ID);
                    changeRole(WARD_ID, Role.GUARDIAN);
                    locked.countDown();
                    await(release);
                }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> request = CompletableFuture.runAsync(() ->
                connectionService.requestConnectionAsGuardian(GUARDIAN_ID, requestDto()));

        Thread.sleep(BLOCK_CHECK_MILLIS);
        assertThat(request).as("역할 변경이 커밋될 때까지 요청은 기다려야 한다").isNotDone();

        release.countDown();
        roleChange.get(10, TimeUnit.SECONDS);

        assertThatThrownBy(() -> request.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CONNECTION_ROLE);
        assertThat(liveConnections()).isEmpty();
    }

    @Test
    @DisplayName("연결 요청이 먼저 잠그면 역할 변경은 그 커밋을 기다렸다가 요청까지 보고 취소한다")
    void 요청이_먼저면_역할변경이_정리한다() throws Exception {
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // requestConnectionAsGuardian이 하는 순서(잠금 → 검증 → INSERT) 중 INSERT 직후, 커밋 전에서 멈춘 상황
        CompletableFuture<Void> request = CompletableFuture.runAsync(() ->
                tx().executeWithoutResult(status -> {
                    userRepository.lockForConnectionChange(List.of(GUARDIAN_ID, WARD_ID));
                    connectionRepository.saveAndFlush(Connection.builder()
                            .guardianId(GUARDIAN_ID)
                            .wardId(WARD_ID)
                            .status(ConnectionStatus.PENDING)
                            .initiatedBy(GUARDIAN_ID)
                            .relation("아들")
                            .build());
                    inserted.countDown();
                    await(release);
                }));
        assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Integer> roleChange = CompletableFuture.supplyAsync(() ->
                tx().execute(status -> {
                    userRepository.lockForAccountChange(WARD_ID);
                    changeRole(WARD_ID, Role.GUARDIAN);
                    return connectionService.tearDownConnectionsOnRoleChange(WARD_ID);
                }));

        Thread.sleep(BLOCK_CHECK_MILLIS);
        assertThat(roleChange).as("연결 요청이 커밋될 때까지 역할 변경은 기다려야 한다").isNotDone();

        release.countDown();
        request.get(10, TimeUnit.SECONDS);

        assertThat(roleChange.get(10, TimeUnit.SECONDS)).as("정리 조회가 방금 커밋된 요청을 본다").isEqualTo(1);
        assertThat(liveConnections()).isEmpty();
        assertThat(connectionRepository.findByGuardianIdOrderByCreatedAtDesc(GUARDIAN_ID))
                .extracting(Connection::getStatus)
                .containsExactly(ConnectionStatus.CANCELLED);
    }

    @Test
    @DisplayName("연결 요청끼리는 서로 막지 않는다 - FOR SHARE는 공유 잠금이다")
    void 연결요청_잠금은_서로_막지_않는다() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() ->
                tx().executeWithoutResult(status -> {
                    userRepository.lockForConnectionChange(List.of(GUARDIAN_ID, WARD_ID));
                    locked.countDown();
                    await(release);
                }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        List<String> second = CompletableFuture.supplyAsync(() ->
                        tx().execute(status -> userRepository.lockForConnectionChange(List.of(WARD_ID, GUARDIAN_ID))))
                .get(5, TimeUnit.SECONDS);

        assertThat(second).containsExactly(GUARDIAN_ID, WARD_ID);
        release.countDown();
        first.get(10, TimeUnit.SECONDS);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private void changeRole(String userId, Role role) {
        User user = userRepository.findById(userId).orElseThrow();
        user.updateRole(role);
    }

    private List<Connection> liveConnections() {
        return connectionRepository.findByGuardianIdAndStatusInOrderByCreatedAtDesc(
                GUARDIAN_ID, List.of(ConnectionStatus.PENDING, ConnectionStatus.ACTIVE));
    }

    private static ConnectionRequestDto requestDto() {
        ConnectionRequestDto dto = mock(ConnectionRequestDto.class);
        when(dto.getTargetId()).thenReturn(WARD_ID);
        when(dto.getRelation()).thenReturn("아들");
        return dto;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

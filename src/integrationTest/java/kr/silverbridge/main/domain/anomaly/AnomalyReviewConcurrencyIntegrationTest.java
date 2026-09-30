package kr.silverbridge.main.domain.anomaly;

import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyVerdict;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.anomaly.service.GuardianAnomalyService;
import kr.silverbridge.main.domain.camera.service.CameraIdentifierFactory;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.DetectedType;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.domain.user.service.UserIdGenerator;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이상감지 판정이 동시 쓰기에 덮어써지지 않는가 (E-2, 2026-09-30).
 *
 * <p>상황 행의 {@code review_status}는 두 경로가 고친다. ① 보호자 응답끼리 - 서로의 표를 못 본 채 확정하면 동수가
 * 한쪽으로 남는다({@code findByIdForUpdate}의 쓰기 잠금으로 막는다). ② AI 감지 승계와 응답 - 감지 쪽이 읽어 둔 옛
 * 판정을 전체 컬럼 UPDATE로 다시 쓴다({@code @DynamicUpdate}로 막는다). 둘 다 커밋 순서가 결과를 바꾸므로 실제 DB에서
 * 트랜잭션을 나눠 돌린다.</p>
 *
 * <p>테스트 트랜잭션을 끄고 실제로 커밋한다. 전용 ID를 쓰고 끝나면 회원 삭제(CASCADE)로 상황·응답까지 정리한다.</p>
 */
@Import({
        GuardianAnomalyService.class,
        ConnectionService.class,
        CameraService.class,
        CameraIdentifierFactory.class,
        UserIdGenerator.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnomalyReviewConcurrencyIntegrationTest extends PostgresIntegrationTest {

    private static final String GUARDIAN_A = "GRC001";
    private static final String GUARDIAN_B = "GRC002";
    private static final String WARD_ID = "WRC001";

    /** 경합은 확률적이라 여러 번 돌린다. 잠금이 있으면 몇 번을 돌려도 결과가 같아야 한다. */
    private static final int ROUNDS = 20;

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private GuardianAnomalyService guardianAnomalyService;
    @Autowired private AnomalyIncidentRepository incidentRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user(GUARDIAN_A, "보호자A", Role.GUARDIAN));
        userRepository.save(TestData.user(GUARDIAN_B, "보호자B", Role.GUARDIAN));
        userRepository.save(TestData.user(WARD_ID, "피보호자", Role.WARD));
        for (String guardianId : List.of(GUARDIAN_A, GUARDIAN_B)) {
            connectionRepository.save(Connection.builder()
                    .guardianId(guardianId)
                    .wardId(WARD_ID)
                    .status(ConnectionStatus.ACTIVE)
                    .initiatedBy(guardianId)
                    .build());
        }
    }

    @AfterEach
    void tearDown() {
        // 연결·상황·응답·동수 안내 기록은 회원 삭제의 CASCADE로 함께 지워진다
        userRepository.deleteAllById(List.of(GUARDIAN_A, GUARDIAN_B, WARD_ID));
    }

    @Test
    @DisplayName("두 보호자가 동시에 반대로 응답해도 동수(CONFLICTED)로 남는다")
    void 동시_반대응답은_동수가_된다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                Long incidentId = openIncident();
                CyclicBarrier start = new CyclicBarrier(2);

                CompletableFuture<?> a = CompletableFuture.runAsync(
                        () -> respondAt(start, GUARDIAN_A, incidentId, AnomalyVerdict.REAL), pool);
                CompletableFuture<?> b = CompletableFuture.runAsync(
                        () -> respondAt(start, GUARDIAN_B, incidentId, AnomalyVerdict.FALSE_ALARM), pool);
                CompletableFuture.allOf(a, b).get(30, TimeUnit.SECONDS);

                assertThat(reviewStatusOf(incidentId))
                        .as("round %d", round)
                        .isEqualTo(AnomalyReviewStatus.CONFLICTED);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("응답이 확정된 뒤 먼저 읽어 둔 감지 승계가 커밋돼도 판정은 되돌아가지 않는다")
    void 감지승계는_판정을_덮어쓰지_않는다() {
        Long incidentId = openIncident();

        // 감지 트랜잭션이 상황을 읽은 뒤, 그 사이에 보호자 응답이 먼저 커밋되고, 감지가 마지막에 커밋되는 순서
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            AnomalyIncident detecting = incidentRepository.findById(incidentId).orElseThrow();

            CompletableFuture.runAsync(() ->
                    guardianAnomalyService.submitFeedback(GUARDIAN_A, incidentId, AnomalyVerdict.REAL)).join();

            detecting.addDetection(OffsetDateTime.now(), 0.95);
        });

        AnomalyIncident saved = incidentRepository.findById(incidentId).orElseThrow();
        assertThat(saved.getReviewStatus()).isEqualTo(AnomalyReviewStatus.REAL);
        assertThat(saved.getEventCount()).isEqualTo(2);
    }

    private void respondAt(CyclicBarrier start, String guardianId, Long incidentId, AnomalyVerdict verdict) {
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        guardianAnomalyService.submitFeedback(guardianId, incidentId, verdict);
    }

    private Long openIncident() {
        return incidentRepository.save(AnomalyIncident.builder()
                .wardId(WARD_ID)
                .sessionId("sess-rc")
                .detectedType(DetectedType.FIRE)
                .detectedAt(OffsetDateTime.now())
                .confidence(0.8)
                .build()).getId();
    }

    private AnomalyReviewStatus reviewStatusOf(Long incidentId) {
        return incidentRepository.findById(incidentId).orElseThrow().getReviewStatus();
    }
}

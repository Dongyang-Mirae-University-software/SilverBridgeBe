package kr.silverbridge.main.domain.notification;

import kr.silverbridge.main.domain.anomaly.service.GuardianAnomalySettingService;
import kr.silverbridge.main.domain.notification.dto.GuardianNotificationTypeSettingRequest;
import kr.silverbridge.main.domain.notification.repository.GuardianNotificationPreferenceRepository;
import kr.silverbridge.main.domain.notification.service.GuardianNotificationPreferenceService;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보호자 알림 종류 설정(V59) - 마이그레이션·UNIQUE·동시 최초 저장.
 *
 * <p>행이 없을 때 동시에 처음 저장해도 모두 성공하고 행은 하나여야 한다({@code ON CONFLICT DO NOTHING}).
 * 테스트 트랜잭션을 끄고 실제로 커밋하며, 회원 삭제(CASCADE)로 정리한다.</p>
 */
@Import({GuardianNotificationPreferenceService.class, GuardianAnomalySettingService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GuardianNotificationPreferenceIntegrationTest extends PostgresIntegrationTest {

    private static final String GUARDIAN_ID = "GNP001";
    private static final int ROUNDS = 20;
    private static final int CONCURRENCY = 3;

    @Autowired private GuardianNotificationPreferenceService service;
    @Autowired private GuardianNotificationPreferenceRepository repository;
    @Autowired private kr.silverbridge.main.domain.anomaly.repository.GuardianAnomalySettingRepository anomalySettingRepository;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user(GUARDIAN_ID, "보호자", Role.GUARDIAN));
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAllById(List.of(GUARDIAN_ID));
    }

    @Test
    @DisplayName("행이 없으면 기본값 ON, 끄면 저장되고 켜면 같은 행이 갱신된다")
    void 기본값과_왕복() {
        assertThat(service.getSettings(GUARDIAN_ID).medication().enabled()).isTrue();

        service.updateSettings(GUARDIAN_ID, new GuardianNotificationTypeSettingRequest(null, false));
        assertThat(service.getSettings(GUARDIAN_ID).medication().enabled()).isFalse();
        assertThat(service.medicationDisabledGuardians()).contains(GUARDIAN_ID);

        service.updateSettings(GUARDIAN_ID, new GuardianNotificationTypeSettingRequest(null, true));
        assertThat(service.getSettings(GUARDIAN_ID).medication().enabled()).isTrue();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("재촉 설정도 동시에 처음 저장해도 모두 성공한다 (guardian_anomaly_setting ON CONFLICT)")
    void 재촉_동시_최초저장() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                anomalySettingRepository.deleteAll(anomalySettingRepository.findAll());
                CyclicBarrier start = new CyclicBarrier(CONCURRENCY);
                CompletableFuture<?>[] futures = new CompletableFuture<?>[CONCURRENCY];
                for (int i = 0; i < CONCURRENCY; i++) {
                    futures[i] = CompletableFuture.runAsync(() -> {
                        try {
                            start.await(10, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        service.updateSettings(GUARDIAN_ID, new GuardianNotificationTypeSettingRequest(false, null));
                    }, pool);
                }
                CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);

                assertThat(anomalySettingRepository.findByGuardianId(GUARDIAN_ID)).as("round %d", round)
                        .hasValueSatisfying(s -> assertThat(s.isReviewReminderEnabled()).isFalse());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("동시에 처음 저장해도 모두 성공하고 보호자당 행은 하나다")
    void 동시_최초저장() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                repository.deleteAll(repository.findAll());
                CyclicBarrier start = new CyclicBarrier(CONCURRENCY);
                CompletableFuture<?>[] futures = new CompletableFuture<?>[CONCURRENCY];
                for (int i = 0; i < CONCURRENCY; i++) {
                    futures[i] = CompletableFuture.runAsync(() -> {
                        try {
                            start.await(10, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        service.updateSettings(GUARDIAN_ID, new GuardianNotificationTypeSettingRequest(null, false));
                    }, pool);
                }
                CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);

                assertThat(repository.findAll()).as("round %d", round).singleElement()
                        .satisfies(p -> assertThat(p.isMedicationEnabled()).isFalse());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}

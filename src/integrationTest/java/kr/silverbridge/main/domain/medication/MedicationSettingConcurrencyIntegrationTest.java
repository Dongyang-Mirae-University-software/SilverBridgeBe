package kr.silverbridge.main.domain.medication;

import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionRequestLimiter;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.medication.config.MedicationProperties;
import kr.silverbridge.main.domain.medication.entity.GuardianMedicationSetting;
import kr.silverbridge.main.domain.medication.entity.MedicationSetting;
import kr.silverbridge.main.domain.medication.repository.GuardianMedicationSettingRepository;
import kr.silverbridge.main.domain.medication.repository.MedicationSettingRepository;
import kr.silverbridge.main.domain.medication.service.GuardianMedicationSettingService;
import kr.silverbridge.main.domain.medication.service.MedicationSettingService;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 복약 설정 행이 처음 만들어지는 순간 동시 저장이 와도 실패(409)하지 않는가 (MED-G13).
 *
 * <p>행이 없는 상태에서 두 요청이 모두 "없음"을 읽고 INSERT하면 늦은 쪽이 UNIQUE 위반으로 실패했다.
 * {@code INSERT … ON CONFLICT DO NOTHING} 후 재조회로 바꿨으므로, 두 요청이 모두 성공하고 행은 하나이며
 * 각자 보낸 필드가 모두 반영되어야 한다. 커밋 순서가 결과를 바꾸므로 테스트 트랜잭션을 끄고 실제로 커밋한다.</p>
 */
@Import({
        MedicationSettingService.class,
        GuardianMedicationSettingService.class,
        ConnectionService.class,
        MedicationProperties.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MedicationSettingConcurrencyIntegrationTest extends PostgresIntegrationTest {

    private static final String GUARDIAN_A = "GSC001";
    private static final String GUARDIAN_B = "GSC002";
    private static final String WARD_ID = "WSC001";

    /** 경합은 확률적이라 여러 번 돌린다. */
    private static final int ROUNDS = 20;

    @Autowired private MedicationSettingService medicationSettingService;
    @Autowired private GuardianMedicationSettingService guardianMedicationSettingService;
    @Autowired private MedicationSettingRepository medicationSettingRepository;
    @Autowired private GuardianMedicationSettingRepository guardianMedicationSettingRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private UserRepository userRepository;

    // ConnectionService의 의존성일 뿐 이 테스트의 관심사가 아니다(Redis 없이 뜨도록 목으로 둔다)
    @MockitoBean private ConnectionRequestLimiter connectionRequestLimiter;

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
        // 연결·설정 행은 회원 삭제의 CASCADE로 함께 지워진다
        userRepository.deleteAllById(List.of(GUARDIAN_A, GUARDIAN_B, WARD_ID));
    }

    @Test
    @DisplayName("복약 알림 설정 - 두 보호자가 동시에 처음 저장해도 둘 다 성공하고 두 값이 모두 반영된다")
    void 복약알림설정_동시_최초저장() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                medicationSettingRepository.findByUserId(WARD_ID).ifPresent(medicationSettingRepository::delete);
                CyclicBarrier start = new CyclicBarrier(2);

                CompletableFuture<?> alarmOff = CompletableFuture.runAsync(() -> {
                    await(start);
                    medicationSettingService.updatePreference(WARD_ID, false, null);
                }, pool);
                CompletableFuture<?> remindOff = CompletableFuture.runAsync(() -> {
                    await(start);
                    medicationSettingService.updatePreference(WARD_ID, null, false);
                }, pool);
                // 어느 쪽이든 예외가 나면 get()이 던져 테스트가 실패한다
                CompletableFuture.allOf(alarmOff, remindOff).get(30, TimeUnit.SECONDS);

                MedicationSetting saved = medicationSettingRepository.findByUserId(WARD_ID).orElseThrow();
                assertThat(saved.isAlarmEnabled()).as("round %d", round).isFalse();
                assertThat(saved.isRemindAgainEnabled()).as("round %d", round).isFalse();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("미복용 요약 설정 - 한 보호자가 두 탭에서 동시에 처음 저장해도 둘 다 성공한다")
    void 미복용요약설정_동시_최초저장() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                guardianMedicationSettingRepository.findByGuardianIdAndWardId(GUARDIAN_A, WARD_ID)
                        .ifPresent(guardianMedicationSettingRepository::delete);
                CyclicBarrier start = new CyclicBarrier(2);

                CompletableFuture<?> disable = CompletableFuture.runAsync(() -> {
                    await(start);
                    guardianMedicationSettingService.update(GUARDIAN_A, WARD_ID, false, null);
                }, pool);
                CompletableFuture<?> setTime = CompletableFuture.runAsync(() -> {
                    await(start);
                    guardianMedicationSettingService.update(GUARDIAN_A, WARD_ID, null, LocalTime.of(19, 30));
                }, pool);
                CompletableFuture.allOf(disable, setTime).get(30, TimeUnit.SECONDS);

                GuardianMedicationSetting saved = guardianMedicationSettingRepository
                        .findByGuardianIdAndWardId(GUARDIAN_A, WARD_ID).orElseThrow();
                assertThat(saved.isMissedAlertEnabled()).as("round %d", round).isFalse();
                assertThat(saved.getMissedAlertTime()).as("round %d", round).isEqualTo(LocalTime.of(19, 30));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CyclicBarrier start) {
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

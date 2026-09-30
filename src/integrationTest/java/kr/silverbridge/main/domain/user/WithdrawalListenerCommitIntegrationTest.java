package kr.silverbridge.main.domain.user;

import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.event.ConnectionDisconnectedEvent;
import kr.silverbridge.main.domain.connection.listener.UserWithdrawalConnectionListener;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import kr.silverbridge.main.domain.medication.repository.MedicationRepository;
import kr.silverbridge.main.domain.medication.service.MedicationWithdrawalService;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 탈퇴 리스너가 AFTER_COMMIT에서 한 쓰기가 실제로 커밋되는가 (audit-index H-1, 2026-09-30).
 *
 * <p>탈퇴 리스너는 <b>동기</b> AFTER_COMMIT이라 탈퇴 트랜잭션의 커밋 직후, 그 트랜잭션 자원이 아직 스레드에 묶인
 * 상태에서 불린다. 여기서 부르는 서비스가 기본 전파(REQUIRED)면 이미 커밋된 트랜잭션에 합류해 쓰기가 다시 커밋되지
 * 않는다({@code NotificationLogAfterCommitIntegrationTest}의 대조군이 같은 현상을 고정한다). 행 자체는 뒤이은 purge의
 * FK CASCADE가 지워 겉으로는 무해해 보이지만, <b>그 트랜잭션 안에서 발행한 연결 해제 이벤트</b>도 AFTER_COMMIT을
 * 영영 맞지 못해 상대방 해제 알림이 사라진다 - 그래서 이벤트 도달까지 함께 본다.</p>
 *
 * <p>테스트 트랜잭션을 끄고 실제로 커밋한다. 전용 ID를 쓰고 끝나면 회원 삭제(CASCADE)로 정리한다.</p>
 */
@Import({
        ConnectionService.class,
        UserWithdrawalConnectionListener.class,
        MedicationWithdrawalService.class,
        WithdrawalListenerCommitIntegrationTest.DisconnectedEventRecorder.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WithdrawalListenerCommitIntegrationTest extends PostgresIntegrationTest {

    private static final String GUARDIAN_ID = "GWL001";
    private static final String WARD_ID = "WWL001";

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private MedicationWithdrawalService medicationWithdrawalService;
    @Autowired private UserRepository userRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private MedicationRepository medicationRepository;
    @Autowired private DisconnectedEventRecorder recorder;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user(GUARDIAN_ID, "탈퇴보호자", Role.GUARDIAN));
        userRepository.save(TestData.user(WARD_ID, "피보호자", Role.WARD));
        recorder.events.clear();
    }

    @AfterEach
    void tearDown() {
        // 연결·약은 회원 삭제의 CASCADE로 함께 지워진다
        userRepository.deleteAllById(List.of(GUARDIAN_ID, WARD_ID));
    }

    @Test
    @DisplayName("탈퇴 커밋 후 연결 정리 리스너가 ACTIVE 연결을 DISCONNECTED로 커밋하고, 해제 이벤트가 AFTER_COMMIT에 도달한다")
    void 탈퇴_연결정리는_커밋되고_해제이벤트가_도달한다() {
        Long connectionId = connectionRepository.save(Connection.builder()
                .guardianId(GUARDIAN_ID)
                .wardId(WARD_ID)
                .status(ConnectionStatus.ACTIVE)
                .initiatedBy(GUARDIAN_ID)
                .build()).getId();

        // 실제 탈퇴와 같은 자리: 트랜잭션 안에서 발행 → 커밋 → 동기 AFTER_COMMIT 리스너
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                eventPublisher.publishEvent(new UserWithdrawnEvent(GUARDIAN_ID, "127.0.0.1", "test")));

        assertThat(connectionRepository.findById(connectionId))
                .get()
                .extracting(Connection::getStatus)
                .isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(recorder.events)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.notifyTargetId()).isEqualTo(WARD_ID);
                    assertThat(event.guardianId()).isEqualTo(GUARDIAN_ID);
                    assertThat(event.wardId()).isEqualTo(WARD_ID);
                });
    }

    @Test
    @DisplayName("탈퇴 커밋 후 복약 정리가 등록 보호자의 약을 실제로 삭제한다")
    void 탈퇴_복약정리는_커밋된다() {
        medicationRepository.save(Medication.builder()
                .wardId(WARD_ID)
                .createdBy(GUARDIAN_ID)
                .name("혈압약")
                .timeSlot(MedicationTimeSlot.MORNING)
                .doseTime(LocalTime.of(8, 0))
                .doseAmount(1)
                .build());

        runAfterCommit(() -> medicationWithdrawalService.removeMedicationsRegisteredBy(GUARDIAN_ID));

        assertThat(medicationRepository.findByCreatedBy(GUARDIAN_ID)).isEmpty();
    }

    /** 바깥 트랜잭션을 커밋하고, 커밋 직후 콜백에서 {@code action}을 실행한다(동기 AFTER_COMMIT 리스너와 같은 자리). */
    private void runAfterCommit(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                }));
    }

    /** 실제 알림 리스너({@code ConnectionNotificationListener})와 같은 단계에서 해제 이벤트를 받아 적는다. */
    static class DisconnectedEventRecorder {

        final List<ConnectionDisconnectedEvent> events = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void on(ConnectionDisconnectedEvent event) {
            events.add(event);
        }
    }
}

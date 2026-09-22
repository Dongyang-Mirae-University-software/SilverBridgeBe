package kr.silverbridge.main.domain.notification;

import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.notification.entity.NotificationLog;
import kr.silverbridge.main.domain.notification.entity.NotificationLogResult;
import kr.silverbridge.main.domain.notification.repository.NotificationLogRepository;
import kr.silverbridge.main.domain.notification.service.NotificationLogService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 커밋이 끝난 뒤(AFTER_COMMIT)에 남기는 알림 이력이 실제로 커밋되는가 (2026-09-22 영향 범위 점검 M-1).
 *
 * <p>{@code MedicationWithdrawalListener}는 <b>동기</b> AFTER_COMMIT 리스너라 디스패처가 탈퇴 트랜잭션의 커밋 직후,
 * 그 트랜잭션 자원이 아직 스레드에 묶인 상태에서 불린다. 이때 기본 전파(REQUIRED)로 저장하면 이미 커밋된 트랜잭션에
 * 합류해 다시 커밋되지 않고 조용히 사라진다 - {@code NotificationLogService.record()}가 REQUIRES_NEW인 이유다.
 * 단위 테스트는 기록 서비스를 목으로 대체해 이것을 볼 수 없어 실제 DB로 고정한다.</p>
 *
 * <p>이 테스트는 테스트 트랜잭션을 끄고(롤백 없음) 실제로 커밋한다. 컨테이너를 다른 테스트와 공유하므로
 * 전용 ID를 쓰고 끝나면 직접 지운다.</p>
 */
@Import(NotificationLogService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationLogAfterCommitIntegrationTest extends PostgresIntegrationTest {

    private static final String GUARDIAN_ID = "GAC001";
    private static final String WARD_ID = "WAC001";

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private NotificationLogService notificationLogService;
    @Autowired private NotificationLogRepository notificationLogRepository;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user(GUARDIAN_ID, "남은보호자", Role.GUARDIAN));
        userRepository.save(TestData.user(WARD_ID, "피보호자", Role.WARD));
    }

    @AfterEach
    void tearDown() {
        // 이력은 사용자 삭제의 CASCADE로 함께 지워진다
        userRepository.deleteAllById(List.of(GUARDIAN_ID, WARD_ID));
    }

    @Test
    @DisplayName("afterCommit 안에서 NotificationLogService.record()로 남긴 이력은 커밋된다(REQUIRES_NEW)")
    void afterCommit_기록서비스는_커밋된다() {
        runAfterCommit(() -> notificationLogService.record(log()));

        assertThat(committedLogs()).hasSize(1);
    }

    @Test
    @DisplayName("대조군: 같은 자리에서 저장소에 바로 저장하면(기본 전파) 커밋된 트랜잭션에 합류해 남지 않는다")
    void afterCommit_기본전파는_사라진다() {
        runAfterCommit(() -> notificationLogRepository.save(log()));

        // 이 대조군이 1건이 되면 이 테스트의 전제가 틀린 것이다 - REQUIRES_NEW의 근거를 다시 볼 것
        assertThat(committedLogs()).isEmpty();
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

    private List<NotificationLog> committedLogs() {
        return notificationLogRepository.findAll().stream()
                .filter(log -> GUARDIAN_ID.equals(log.getRecipientId()))
                .toList();
    }

    private static NotificationLog log() {
        return NotificationLog.builder()
                .createdAt(OffsetDateTime.now())
                .type(NotificationType.MEDICATION_STOPPED)
                .recipientId(GUARDIAN_ID)
                .wardId(WARD_ID)
                .title("복약 일정 중지")
                .body("탈퇴한 보호자가 등록한 피보호자님의 약 1건이 중지되었습니다.")
                .result(NotificationLogResult.DELIVERED)
                .channelResults(List.of())
                .build();
    }
}

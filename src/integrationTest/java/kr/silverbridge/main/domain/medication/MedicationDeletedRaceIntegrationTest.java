package kr.silverbridge.main.domain.medication;

import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.repository.MedicationRepository;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 약 수정과 삭제가 겹쳐도 삭제가 취소되지 않는다 - {@code Medication}의 {@code @DynamicUpdate}를 실제 DB로 고정한다
 * (2026-10-23 약 여러 개 삭제 점검 L-1).
 *
 * <p>보호자 A의 수정 트랜잭션이 약을 읽고 → 보호자 B의 삭제 트랜잭션이 먼저 커밋되고 → A가 커밋하는 순서를 한 스레드에서
 * 재현한다(B는 REQUIRES_NEW로 A를 잠시 멈추고 따로 커밋). 전체 컬럼 UPDATE였을 때는 A가 읽어 둔 {@code deleted_at = NULL}을
 * 다시 써서 지운 약이 되살아나고 복약 알림이 계속 나갔다.</p>
 *
 * <p>테스트 트랜잭션을 끄고 실제로 커밋한다. 전용 ID를 쓰고 끝나면 회원 삭제(CASCADE)로 정리한다.</p>
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MedicationDeletedRaceIntegrationTest extends PostgresIntegrationTest {

    private static final String WARD_ID = "WDR101";
    private static final String GUARDIAN_ID = "GDR101";

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MedicationRepository medicationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long medicationId;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user(WARD_ID, "피보호자", Role.WARD));
        userRepository.save(TestData.user(GUARDIAN_ID, "보호자", Role.GUARDIAN));
        medicationId = medicationRepository.save(TestData.medication(WARD_ID, GUARDIAN_ID, "혈압약")).getId();
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAllById(List.of(WARD_ID, GUARDIAN_ID));
    }

    @Test
    @DisplayName("수정이 읽은 뒤 삭제가 먼저 커밋돼도, 수정 커밋이 deleted_at을 되돌리지 않는다")
    void 수정중_삭제커밋_삭제유지() {
        TransactionTemplate editTx = new TransactionTemplate(transactionManager);
        TransactionTemplate deleteTx = new TransactionTemplate(transactionManager);
        deleteTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        editTx.executeWithoutResult(status -> {
            // 보호자 A: 약을 읽고 이름·시각을 고친다(아직 커밋 전)
            Medication editing = medicationRepository.findById(medicationId).orElseThrow();
            editing.update("혈압약 5mg", editing.getTimeSlot(), LocalTime.of(9, 0), BigDecimal.ONE, null);

            // 보호자 B: 그 사이 같은 약을 지우고 먼저 커밋한다
            deleteTx.executeWithoutResult(inner ->
                    medicationRepository.findById(medicationId).orElseThrow().delete(OffsetDateTime.now()));
        });

        // 삭제는 그대로, 수정한 칸도 반영(바뀐 컬럼만 쓰므로 서로 덮지 않는다)
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM medication WHERE id = ?", Boolean.class, medicationId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM medication WHERE id = ?", String.class, medicationId)).isEqualTo("혈압약 5mg");
    }
}

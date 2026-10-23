package kr.silverbridge.main.domain.medication;

import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.event.MedicationIntakeChangedEvent;
import kr.silverbridge.main.domain.medication.repository.MedicationRepository;
import kr.silverbridge.main.domain.medication.service.WardMedicationService;
import kr.silverbridge.main.domain.user.repository.UserRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 복용 묶음 체크의 "전부 성공 또는 전부 실패"를 실제 PostgreSQL로 고정한다(2026-10-23 점검 L-3).
 *
 * <p>단위 테스트는 남의 약에서 예외가 나는 것까지만 본다. 여기서는 내 약을 먼저 체크한 뒤 남의 약에서 403이 나면
 * 앞서 저장한 체크가 롤백되고, 보호자 화면 갱신 이벤트(AFTER_COMMIT)도 나가지 않는지를 실제 커밋으로 확인한다.
 * 잠금은 ID 오름차순이라 남의 약을 가장 나중에 만들어 내 약이 먼저 처리되게 한다.</p>
 *
 * <p>테스트 트랜잭션을 끄고 실제로 커밋한다. 전용 ID를 쓰고 끝나면 직접 지운다.</p>
 */
@Import({
        WardMedicationService.class,
        MedicationIntakeBatchIntegrationTest.IntakeEventRecorder.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MedicationIntakeBatchIntegrationTest extends PostgresIntegrationTest {

    private static final String WARD_ID = "WIB101";
    private static final String OTHER_WARD_ID = "WIB102";
    private static final String GUARDIAN_ID = "GIB101";

    @Autowired private WardMedicationService service;
    @Autowired private MedicationRepository medicationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private IntakeEventRecorder recorder;

    private Long mine1;
    private Long mine2;
    private Long others;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user(WARD_ID, "피보호자", Role.WARD));
        userRepository.save(TestData.user(OTHER_WARD_ID, "다른피보호자", Role.WARD));
        userRepository.save(TestData.user(GUARDIAN_ID, "보호자", Role.GUARDIAN));
        mine1 = medicationRepository.save(TestData.medication(WARD_ID, GUARDIAN_ID, "혈압약")).getId();
        mine2 = medicationRepository.save(TestData.medication(WARD_ID, GUARDIAN_ID, "위장약")).getId();
        // 가장 큰 ID - 오름차순 잠금에서 마지막에 처리된다
        Medication other = medicationRepository.save(TestData.medication(OTHER_WARD_ID, GUARDIAN_ID, "남의약"));
        others = other.getId();
        recorder.events.clear();
    }

    @AfterEach
    void tearDown() {
        // 약·복용 기록은 회원 삭제의 CASCADE로 함께 지워진다
        userRepository.deleteAllById(List.of(WARD_ID, OTHER_WARD_ID, GUARDIAN_ID));
    }

    @Test
    @DisplayName("남의 약이 섞이면 403 - 먼저 처리한 내 약 체크도 롤백되고 이벤트는 0건(전부 실패)")
    void 남의약섞임_전부롤백() {
        assertThatThrownBy(() -> service.markBatch(WARD_ID, List.of(others, mine1, mine2), true))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEDICATION_NOT_OWNED);

        assertThat(intakeCount()).isZero();
        assertThat(recorder.events).isEmpty();
    }

    @Test
    @DisplayName("내 약만이면 전부 체크되고 커밋 뒤 약마다 이벤트가 나간다")
    void 내약만_전부체크() {
        service.markBatch(WARD_ID, List.of(mine2, mine1), true);

        assertThat(intakeCount()).isEqualTo(2);
        assertThat(recorder.events).extracting(MedicationIntakeChangedEvent::medicationId)
                .containsExactlyInAnyOrder(mine1, mine2);
    }

    private long intakeCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM medication_intake WHERE medication_id IN (?, ?)", Long.class, mine1, mine2);
    }

    /** 실제 화면 갱신 리스너({@code MedicationIntakeNotificationListener})와 같은 단계에서 이벤트를 받아 적는다. */
    static class IntakeEventRecorder {

        final List<MedicationIntakeChangedEvent> events = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void on(MedicationIntakeChangedEvent event) {
            events.add(event);
        }
    }
}

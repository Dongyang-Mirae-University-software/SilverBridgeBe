package kr.silverbridge.main.domain.medication;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.medication.repository.MedicationRepository;
import kr.silverbridge.main.domain.medication.service.GuardianMedicationService;
import kr.silverbridge.main.domain.medication.service.GuardianMedicationSettingService;
import kr.silverbridge.main.domain.medication.service.MedicationSettingService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 약 여러 개 삭제의 "전부 성공 또는 전부 실패"를 실제 PostgreSQL로 고정한다(2026-10-23).
 *
 * <p>연결된 피보호자 경로로 다른 피보호자의 약을 섞으면 403이고, 그 전에 처리한 내 약 삭제도 롤백돼야 한다.
 * 처리 순서는 ID 오름차순이라 다른 피보호자의 약을 가장 나중에 만들어 내 약이 먼저 삭제되게 한다.</p>
 *
 * <p>테스트 트랜잭션을 끄고 실제로 커밋한다. 전용 ID를 쓰고 끝나면 회원 삭제(CASCADE)로 정리한다.</p>
 */
@Import(GuardianMedicationService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MedicationDeleteBatchIntegrationTest extends PostgresIntegrationTest {

    private static final String WARD_ID = "WDB101";
    private static final String OTHER_WARD_ID = "WDB102";
    private static final String GUARDIAN_ID = "GDB101";

    @Autowired private GuardianMedicationService service;
    @Autowired private MedicationRepository medicationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private ConnectionService connectionService;
    @MockitoBean private MedicationSettingService medicationSettingService;
    @MockitoBean private GuardianMedicationSettingService guardianMedicationSettingService;

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
        // 가장 큰 ID - 오름차순 처리에서 마지막
        others = medicationRepository.save(TestData.medication(OTHER_WARD_ID, GUARDIAN_ID, "남의약")).getId();
        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAllById(List.of(WARD_ID, OTHER_WARD_ID, GUARDIAN_ID));
    }

    @Test
    @DisplayName("다른 피보호자의 약이 섞이면 403 - 먼저 처리한 내 약 삭제도 롤백(전부 실패)")
    void 다른피보호자약_섞임_전부롤백() {
        assertThatThrownBy(() -> service.deleteBatch(GUARDIAN_ID, WARD_ID, List.of(others, mine1, mine2)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEDICATION_NOT_AUTHORIZED);

        assertThat(deletedCount(WARD_ID)).isZero();
        assertThat(deletedCount(OTHER_WARD_ID)).isZero();
    }

    @Test
    @DisplayName("내 약만이면 전부 soft delete - 행은 남고 deleted_at만 채워진다")
    void 내약만_전부삭제() {
        int deleted = service.deleteBatch(GUARDIAN_ID, WARD_ID, List.of(mine2, mine1));

        assertThat(deleted).isEqualTo(2);
        assertThat(deletedCount(WARD_ID)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM medication WHERE ward_id = ?", Long.class, WARD_ID)).isEqualTo(2);
    }

    private long deletedCount(String wardId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM medication WHERE ward_id = ? AND deleted_at IS NOT NULL", Long.class, wardId);
    }
}

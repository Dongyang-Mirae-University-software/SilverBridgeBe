package kr.silverbridge.main.domain.medication;

import jakarta.persistence.EntityManager;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.medication.dto.MedicationBatchCreateRequest;
import kr.silverbridge.main.domain.medication.dto.MedicationItem;
import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import kr.silverbridge.main.domain.medication.repository.MedicationRepository;
import kr.silverbridge.main.domain.medication.service.GuardianMedicationService;
import kr.silverbridge.main.domain.medication.service.GuardianMedicationSettingService;
import kr.silverbridge.main.domain.medication.service.MedicationSettingService;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 반 알(V62 {@code NUMERIC(3,1)} + CHECK)과 일괄 등록의 원자성을 실제 PostgreSQL로 확인한다(2026-10-22).
 *
 * <p>일괄 등록은 서비스 트랜잭션 하나라, DB가 중간 항목을 거절하면 앞서 들어간 행까지 남지 않아야 한다. DTO 검증을 지나
 * DB까지 간 경우를 만들려고 서비스를 직접 부르고 CHECK에 걸리는 값(0.3)을 중간에 넣는다.</p>
 */
@Import(GuardianMedicationService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MedicationHalfDoseBatchIntegrationTest extends PostgresIntegrationTest {

    private static final String WARD_ID = "WHD101";
    private static final String GUARDIAN_ID = "GHD101";

    @Autowired private GuardianMedicationService service;
    @Autowired private MedicationRepository medicationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private ConnectionService connectionService;
    @MockitoBean private MedicationSettingService medicationSettingService;
    @MockitoBean private GuardianMedicationSettingService guardianMedicationSettingService;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user(WARD_ID, "피보호자", Role.WARD));
        userRepository.save(TestData.user(GUARDIAN_ID, "보호자", Role.GUARDIAN));
        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM medication WHERE ward_id = ?", WARD_ID);
        userRepository.deleteAllById(List.of(GUARDIAN_ID, WARD_ID));
    }

    @Test
    @DisplayName("일괄 등록: 6×2=12건이 반 알 그대로 저장되고 응답은 0.5·1로 정규화된다")
    void batchCreatesAllWithHalfDose() {
        List<MedicationBatchCreateRequest.Drug> drugs = List.of(
                drug("약1", "0.5"), drug("약2", "1"), drug("약3", "1.5"), drug("약4", "2"), drug("약5", null), drug("약6", "99"));
        List<MedicationItem> created = service.createBatch(GUARDIAN_ID, WARD_ID, new MedicationBatchCreateRequest(drugs,
                List.of(new MedicationBatchCreateRequest.Time(LocalTime.of(19, 0)),
                        new MedicationBatchCreateRequest.Time(LocalTime.of(8, 30))), "식후"));

        assertThat(created).hasSize(12);
        assertThat(created.get(0).timeSlot()).isEqualTo(MedicationTimeSlot.MORNING);
        assertThat(created.get(6).timeSlot()).isEqualTo(MedicationTimeSlot.DINNER);
        assertThat(created.get(0).doseAmount().toPlainString()).isEqualTo("0.5");
        assertThat(created.get(1).doseAmount().toPlainString()).isEqualTo("1");
        assertThat(created.get(4).doseAmount().toPlainString()).isEqualTo("1");

        entityManager.clear();
        Medication reloaded = medicationRepository.findById(created.get(0).medicationId()).orElseThrow();
        assertThat(reloaded.getDoseAmount()).isEqualByComparingTo("0.5");
        assertThat(MedicationItem.of(reloaded, null).doseAmount().toPlainString()).isEqualTo("0.5");
        assertThat(count()).isEqualTo(12);
    }

    @Test
    @DisplayName("일괄 등록: 중간 항목이 DB CHECK에 걸리면 앞서 넣은 행까지 0건(전부 실패)")
    void batchRollsBackWhenMiddleItemFails() {
        List<MedicationBatchCreateRequest.Drug> drugs = List.of(drug("약1", "1"), drug("약2", "0.3"), drug("약3", "1"));

        assertThatThrownBy(() -> service.createBatch(GUARDIAN_ID, WARD_ID, new MedicationBatchCreateRequest(drugs,
                List.of(new MedicationBatchCreateRequest.Time(LocalTime.of(8, 0))), null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("V62 CHECK: 0.3·0·100·99.5는 DB가 거절하고 0.5·99는 받는다")
    void checkConstraint() {
        for (String bad : List.of("0.3", "0", "100", "99.5")) {
            assertThatThrownBy(() -> insert(bad)).as(bad).isInstanceOf(DataIntegrityViolationException.class);
        }
        insert("0.5");
        insert("99");
        assertThat(count()).isEqualTo(2);
    }

    private void insert(String amount) {
        jdbcTemplate.update("INSERT INTO medication (ward_id, created_by, name, time_slot, dose_time, dose_amount) "
                + "VALUES (?, ?, '약', 'MORNING', '08:00', ?)", WARD_ID, GUARDIAN_ID, new BigDecimal(amount));
    }

    private long count() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM medication WHERE ward_id = ?", Long.class, WARD_ID);
    }

    private static MedicationBatchCreateRequest.Drug drug(String name, String amount) {
        return new MedicationBatchCreateRequest.Drug(name, amount == null ? null : new BigDecimal(amount));
    }
}

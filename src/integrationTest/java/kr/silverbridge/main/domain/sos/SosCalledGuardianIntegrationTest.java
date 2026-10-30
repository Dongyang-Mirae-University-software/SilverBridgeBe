package kr.silverbridge.main.domain.sos;

import jakarta.persistence.EntityManager;
import kr.silverbridge.main.domain.sos.entity.SosEvent;
import kr.silverbridge.main.domain.sos.entity.SosTriggerType;
import kr.silverbridge.main.domain.sos.repository.SosEventRepository;
import kr.silverbridge.main.domain.sos.repository.SosEventRepository.PressGroupRow;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SOS 이력의 전화받은 보호자(V64 {@code called_guardian_id})가 실제 DB에서 맞게 저장·정리·묶이는가 (2026-10-30).
 *
 * <p>FK {@code ON DELETE SET NULL}과 묶음 파티션은 목 테스트로 실행되지 않는다.</p>
 */
class SosCalledGuardianIntegrationTest extends PostgresIntegrationTest {

    private static final int GAP = 60;
    private static final String WARD_ID = "WCG001";
    private static final String GUARDIAN_A = "GCG001";
    private static final String GUARDIAN_B = "GCG002";

    @Autowired private SosEventRepository sosEventRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    private OffsetDateTime base;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user(WARD_ID, "피보호자", Role.WARD));
        userRepository.save(TestData.user(GUARDIAN_A, "보호자가", Role.GUARDIAN));
        userRepository.save(TestData.user(GUARDIAN_B, "보호자나", Role.GUARDIAN));
        base = OffsetDateTime.now().minusDays(1).truncatedTo(ChronoUnit.SECONDS);
    }

    @Test
    @DisplayName("전화받은 보호자가 저장되고, 그 보호자가 삭제(탈퇴)되면 값만 NULL이 되고 이력은 남는다")
    void 저장과_탈퇴시_SET_NULL() {
        long id = saveAt(SosTriggerType.GUARDIAN_CALL, GUARDIAN_A, base);
        assertThat(sosEventRepository.findById(id)).get()
                .extracting(SosEvent::getCalledGuardianId).isEqualTo(GUARDIAN_A);

        userRepository.deleteById(GUARDIAN_A);
        userRepository.flush();
        entityManager.clear();

        SosEvent kept = sosEventRepository.findById(id).orElseThrow();
        assertThat(kept.getCalledGuardianId()).isNull();
        assertThat(kept.getWardId()).isEqualTo(WARD_ID);
        assertThat(kept.getTriggerType()).isEqualTo(SosTriggerType.GUARDIAN_CALL);
    }

    @Test
    @DisplayName("묶음: 1분 안이어도 다른 보호자에게 건 전화는 따로, 같은 보호자면 한 묶음")
    void 보호자가_다르면_따로_묶는다() {
        saveAt(SosTriggerType.GUARDIAN_CALL, GUARDIAN_A, base);
        long toB = saveAt(SosTriggerType.GUARDIAN_CALL, GUARDIAN_B, base.plusSeconds(10));
        long toA = saveAt(SosTriggerType.GUARDIAN_CALL, GUARDIAN_A, base.plusSeconds(20));

        List<PressGroupRow> rows = sosEventRepository.findPressGroups(List.of(WARD_ID), "", GAP, 50, 0);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getLatestId()).isEqualTo(toA);
        assertThat(rows.get(0).getPressCount()).isEqualTo(2L);
        assertThat(rows.get(1).getLatestId()).isEqualTo(toB);
        assertThat(rows.get(1).getPressCount()).isEqualTo(1L);
        assertThat(sosEventRepository.countPressGroups(List.of(WARD_ID), "", GAP)).isEqualTo(2);
    }

    @Test
    @DisplayName("묶음: 전화받은 보호자가 기록되지 않은 보호자 연락끼리는 묶이고, SOS 버튼 묶음은 그대로다")
    void 미기록_연락과_SOS_버튼() {
        saveAt(SosTriggerType.GUARDIAN_CALL, null, base);
        saveAt(SosTriggerType.GUARDIAN_CALL, null, base.plusSeconds(30));
        saveAt(SosTriggerType.SOS_BUTTON, null, base.plusSeconds(5));
        saveAt(SosTriggerType.SOS_BUTTON, null, base.plusSeconds(15));

        assertThat(sosEventRepository.findPressGroups(List.of(WARD_ID), "", GAP, 50, 0))
                .extracting(PressGroupRow::getPressCount)
                .containsExactly(2L, 2L);
    }

    /** {@code created_at}은 감사 필드(수정 불가 매핑)라 저장 후 네이티브 UPDATE로 시각을 옮긴다. */
    private long saveAt(SosTriggerType triggerType, String calledGuardianId, OffsetDateTime createdAt) {
        Long id = sosEventRepository.saveAndFlush(SosEvent.builder()
                .wardId(WARD_ID)
                .triggerType(triggerType)
                .calledGuardianId(calledGuardianId)
                .build()).getId();
        entityManager.createNativeQuery("UPDATE sos_event SET created_at = :createdAt WHERE id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", id)
                .executeUpdate();
        entityManager.clear();
        return id;
    }
}

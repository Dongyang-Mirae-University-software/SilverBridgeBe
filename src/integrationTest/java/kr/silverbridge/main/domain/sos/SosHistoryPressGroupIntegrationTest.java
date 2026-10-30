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
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보호자 SOS 이력 연타 묶음 쿼리가 실제 DB에서 맞게 묶는가 (2026-10-30).
 *
 * <p>LAG·누적합 윈도 함수와 {@code array_agg}를 쓴 네이티브 쿼리라 목 테스트로는 실행되지 않는다. 묶는 기준은
 * "같은 피보호자·같은 경로에서 앞 누름과 60초 이내"이고, 페이지·건수는 묶음 단위다.</p>
 */
class SosHistoryPressGroupIntegrationTest extends PostgresIntegrationTest {

    private static final int GAP = 60;
    private static final String ALL = "";
    private static final String WARD_A = "WPG001";
    private static final String WARD_B = "WPG002";

    @Autowired private SosEventRepository sosEventRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    /** DB 저장 정밀도(마이크로초)에 흔들리지 않게 초 단위로 자른 기준 시각. */
    private OffsetDateTime base;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user(WARD_A, "피보호자", Role.WARD));
        userRepository.save(TestData.user(WARD_B, "다른피보호자", Role.WARD));
        base = OffsetDateTime.now().minusDays(1).truncatedTo(ChronoUnit.SECONDS);
    }

    @Test
    @DisplayName("59초 간격 연쇄는 한 묶음 - 최신·최초 ID와 누름 수")
    void 간격59초_한묶음() {
        long first = saveAt(WARD_A, SosTriggerType.SOS_BUTTON, base);
        saveAt(WARD_A, SosTriggerType.SOS_BUTTON, base.plusSeconds(59));
        long latest = saveAt(WARD_A, SosTriggerType.SOS_BUTTON, base.plusSeconds(118));

        List<PressGroupRow> rows = groups(List.of(WARD_A), ALL, 0);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getLatestId()).isEqualTo(latest);
        assertThat(rows.get(0).getFirstId()).isEqualTo(first);
        assertThat(rows.get(0).getPressCount()).isEqualTo(3L);
        assertThat(sosEventRepository.countPressGroups(List.of(WARD_A), ALL, GAP)).isEqualTo(1);
    }

    @Test
    @DisplayName("정확히 60초 간격은 같은 묶음, 61초는 두 묶음")
    void 경계_60초는_같고_61초는_다르다() {
        saveAt(WARD_A, SosTriggerType.SOS_BUTTON, base);
        saveAt(WARD_A, SosTriggerType.SOS_BUTTON, base.plusSeconds(60));
        assertThat(sosEventRepository.countPressGroups(List.of(WARD_A), ALL, GAP)).isEqualTo(1);

        saveAt(WARD_B, SosTriggerType.SOS_BUTTON, base);
        saveAt(WARD_B, SosTriggerType.SOS_BUTTON, base.plusSeconds(61));
        List<PressGroupRow> rows = groups(List.of(WARD_B), ALL, 0);
        assertThat(rows).extracting(PressGroupRow::getPressCount).containsExactly(1L, 1L);
    }

    @Test
    @DisplayName("30초 간격으로 5분 동안 누르면 한 묶음 - 시계 칸이 아니라 앞 누름 기준 연쇄")
    void 연쇄_5분_한묶음() {
        for (int i = 0; i <= 10; i++) {
            saveAt(WARD_A, SosTriggerType.SOS_BUTTON, base.plusSeconds(30L * i));
        }

        List<PressGroupRow> rows = groups(List.of(WARD_A), ALL, 0);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getPressCount()).isEqualTo(11L);
    }

    @Test
    @DisplayName("경로가 다르면 시각이 붙어 있어도 따로 묶고, 경로 필터를 걸어도 그 경로 묶음은 같다")
    void 경로가_다르면_따로() {
        saveAt(WARD_A, SosTriggerType.SOS_BUTTON, base);
        long call = saveAt(WARD_A, SosTriggerType.GUARDIAN_CALL, base.plusSeconds(10));
        saveAt(WARD_A, SosTriggerType.SOS_BUTTON, base.plusSeconds(20));

        assertThat(groups(List.of(WARD_A), ALL, 0)).extracting(PressGroupRow::getPressCount)
                .containsExactly(2L, 1L);

        List<PressGroupRow> calls = groups(List.of(WARD_A), SosTriggerType.GUARDIAN_CALL.name(), 0);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).getLatestId()).isEqualTo(call);
        assertThat(sosEventRepository.countPressGroups(List.of(WARD_A), SosTriggerType.SOS_BUTTON.name(), GAP))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("피보호자가 다르면 시각이 붙어 있어도 따로 묶는다")
    void 피보호자가_다르면_따로() {
        saveAt(WARD_A, SosTriggerType.SOS_BUTTON, base);
        saveAt(WARD_B, SosTriggerType.SOS_BUTTON, base.plusSeconds(10));
        saveAt(WARD_A, SosTriggerType.SOS_BUTTON, base.plusSeconds(20));

        assertThat(groups(List.of(WARD_A, WARD_B), ALL, 0)).extracting(PressGroupRow::getPressCount)
                .containsExactly(2L, 1L);
        // 인가 범위 밖 피보호자는 세지 않는다
        assertThat(sosEventRepository.countPressGroups(List.of(WARD_A), ALL, GAP)).isEqualTo(1);
    }

    @Test
    @DisplayName("탈퇴로 ward_id가 NULL인 익명 이력은 묶음에 끼지도, 결과에 나오지도 않는다")
    void 익명_이력은_묶지_않는다() {
        long ward = saveAt(WARD_A, SosTriggerType.SOS_BUTTON, base);
        saveAt(null, SosTriggerType.SOS_BUTTON, base.plusSeconds(5));
        saveAt(null, SosTriggerType.SOS_BUTTON, base.plusSeconds(10));

        List<PressGroupRow> rows = groups(List.of(WARD_A), ALL, 0);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getLatestId()).isEqualTo(ward);
        assertThat(rows.get(0).getPressCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("묶음은 페이지 경계에서 잘리지 않는다 - totalElements·페이지가 묶음 단위, 마지막 누름 최신순")
    void 페이지_경계에서_잘리지_않는다() {
        // 오래된 묶음(1번) → 가운데 묶음(3번) → 최신 묶음(2번)
        saveAt(WARD_A, SosTriggerType.SOS_BUTTON, base);
        long midFirst = saveAt(WARD_A, SosTriggerType.SOS_BUTTON, base.plusMinutes(10));
        saveAt(WARD_A, SosTriggerType.SOS_BUTTON, base.plusMinutes(10).plusSeconds(30));
        long midLatest = saveAt(WARD_A, SosTriggerType.SOS_BUTTON, base.plusMinutes(11));
        saveAt(WARD_A, SosTriggerType.SOS_BUTTON, base.plusMinutes(20));
        long newest = saveAt(WARD_A, SosTriggerType.SOS_BUTTON, base.plusMinutes(20).plusSeconds(45));

        assertThat(sosEventRepository.countPressGroups(List.of(WARD_A), ALL, GAP)).isEqualTo(3);

        List<PressGroupRow> page0 = sosEventRepository.findPressGroups(List.of(WARD_A), ALL, GAP, 1, 0);
        List<PressGroupRow> page1 = sosEventRepository.findPressGroups(List.of(WARD_A), ALL, GAP, 1, 1);
        List<PressGroupRow> page2 = sosEventRepository.findPressGroups(List.of(WARD_A), ALL, GAP, 1, 2);

        assertThat(page0).singleElement().satisfies(row -> {
            assertThat(row.getLatestId()).isEqualTo(newest);
            assertThat(row.getPressCount()).isEqualTo(2L);
        });
        assertThat(page1).singleElement().satisfies(row -> {
            assertThat(row.getLatestId()).isEqualTo(midLatest);
            assertThat(row.getFirstId()).isEqualTo(midFirst);
            assertThat(row.getPressCount()).isEqualTo(3L);
        });
        assertThat(page2).singleElement().extracting(PressGroupRow::getPressCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("경로별 건수는 묶음 수다 - 누름 6번이어도 묶음 3개")
    void 경로별_건수는_묶음수() {
        saveAt(WARD_A, SosTriggerType.SOS_BUTTON, base);
        saveAt(WARD_A, SosTriggerType.SOS_BUTTON, base.plusSeconds(20));
        saveAt(WARD_A, SosTriggerType.SOS_BUTTON, base.plusMinutes(5));
        saveAt(WARD_A, SosTriggerType.GUARDIAN_CALL, base.plusSeconds(1));
        saveAt(WARD_A, SosTriggerType.GUARDIAN_CALL, base.plusSeconds(2));
        saveAt(WARD_B, SosTriggerType.GUARDIAN_CALL, base); // 범위 밖

        Map<String, Long> counts = sosEventRepository.countPressGroupsByTriggerType(List.of(WARD_A), ALL, GAP)
                .stream()
                .collect(Collectors.toMap(SosEventRepository.PressGroupCount::getTriggerType,
                        SosEventRepository.PressGroupCount::getTotal));

        assertThat(counts).containsOnly(
                Map.entry(SosTriggerType.SOS_BUTTON.name(), 2L),
                Map.entry(SosTriggerType.GUARDIAN_CALL.name(), 1L));
    }

    private List<PressGroupRow> groups(List<String> wardIds, String triggerType, long offset) {
        return sosEventRepository.findPressGroups(wardIds, triggerType, GAP, 50, offset);
    }

    /** {@code created_at}은 감사 필드(수정 불가 매핑)라 저장 후 네이티브 UPDATE로 시각을 옮긴다. */
    private long saveAt(String wardId, SosTriggerType triggerType, OffsetDateTime createdAt) {
        Long id = sosEventRepository.saveAndFlush(SosEvent.builder()
                .wardId(wardId)
                .location("거실")
                .triggerType(triggerType)
                .build()).getId();
        entityManager.createNativeQuery("UPDATE sos_event SET created_at = :createdAt WHERE id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", id)
                .executeUpdate();
        entityManager.clear();
        return id;
    }
}

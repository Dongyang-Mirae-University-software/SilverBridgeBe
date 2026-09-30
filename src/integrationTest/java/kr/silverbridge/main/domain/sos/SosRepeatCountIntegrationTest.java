package kr.silverbridge.main.domain.sos;

import jakarta.persistence.EntityManager;
import kr.silverbridge.main.domain.sos.entity.SosEvent;
import kr.silverbridge.main.domain.sos.repository.SosEventRepository;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SOS 반복 횟수 집계 쿼리가 실제 DB에서 창 경계를 지키는가 (#249 L-3, 2026-09-30).
 *
 * <p>{@code countByWardIdAndCreatedAtGreaterThanEqual}은 메서드 이름으로 만든 파생 쿼리라 목 테스트는 그 조건
 * (피보호자 일치, {@code >=} 경계, TIMESTAMPTZ 바인딩)을 실행하지 않는다. 집계 창 시작 시각과 정확히 같은 행은 세고,
 * 1초라도 이르면 세지 않아야 한다.</p>
 */
class SosRepeatCountIntegrationTest extends PostgresIntegrationTest {

    private static final String WARD_ID = "WSR001";
    private static final String OTHER_WARD_ID = "WSR002";

    @Autowired private SosEventRepository sosEventRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("창 시작 시각과 같은 이력은 세고, 그보다 이르거나 다른 피보호자의 이력은 세지 않는다")
    void 집계는_창_경계와_피보호자를_지킨다() {
        userRepository.save(TestData.user(WARD_ID, "피보호자", Role.WARD));
        userRepository.save(TestData.user(OTHER_WARD_ID, "다른피보호자", Role.WARD));

        // DB 저장 정밀도(마이크로초)에 맞춰 잘라 둔다 - 경계 비교가 반올림에 흔들리지 않게
        OffsetDateTime from = OffsetDateTime.now().minusMinutes(10).truncatedTo(ChronoUnit.SECONDS);

        saveAt(WARD_ID, from);                    // 경계 - 센다
        saveAt(WARD_ID, from.minusSeconds(1));    // 창 밖 - 세지 않는다
        saveAt(WARD_ID, from.plusMinutes(9));     // 창 안 - 센다
        saveAt(OTHER_WARD_ID, from.plusMinutes(1)); // 다른 피보호자 - 세지 않는다

        assertThat(sosEventRepository.countByWardIdAndCreatedAtGreaterThanEqual(WARD_ID, from)).isEqualTo(2);
    }

    /** {@code created_at}은 감사 필드(수정 불가 매핑)라 저장 후 네이티브 UPDATE로 시각을 옮긴다. */
    private void saveAt(String wardId, OffsetDateTime createdAt) {
        Long id = sosEventRepository.saveAndFlush(SosEvent.builder()
                .wardId(wardId)
                .location("거실")
                .build()).getId();
        entityManager.createNativeQuery("UPDATE sos_event SET created_at = :createdAt WHERE id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", id)
                .executeUpdate();
        entityManager.clear();
    }
}

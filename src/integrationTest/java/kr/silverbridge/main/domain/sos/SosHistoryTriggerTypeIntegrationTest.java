package kr.silverbridge.main.domain.sos;

import kr.silverbridge.main.domain.sos.entity.SosEvent;
import kr.silverbridge.main.domain.sos.entity.SosTriggerType;
import kr.silverbridge.main.domain.sos.repository.SosEventRepository;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보호자 SOS 이력의 경로 필터·경로별 건수 쿼리가 실제 DB에서 맞는가 (SOS-G12).
 *
 * <p>건수 집계는 JPQL GROUP BY + 인터페이스 프로젝션이라 목 테스트로는 실행되지 않는다. 넘긴 피보호자 범위만 세고,
 * 페이지와 무관하게 전체를 세는지 확인한다.</p>
 */
class SosHistoryTriggerTypeIntegrationTest extends PostgresIntegrationTest {

    private static final String WARD_ID = "WSH001";
    private static final String OTHER_WARD_ID = "WSH002";

    @Autowired private SosEventRepository sosEventRepository;
    @Autowired private UserRepository userRepository;

    @Test
    @DisplayName("경로별 건수는 넘긴 피보호자 범위의 전체를 세고, 경로 필터 조회는 그 경로만 돌려준다")
    void 경로별_건수와_필터() {
        userRepository.save(TestData.user(WARD_ID, "피보호자", Role.WARD));
        userRepository.save(TestData.user(OTHER_WARD_ID, "다른피보호자", Role.WARD));

        for (int i = 0; i < 3; i++) {
            save(WARD_ID, SosTriggerType.SOS_BUTTON);
        }
        save(WARD_ID, SosTriggerType.GUARDIAN_CALL);
        save(OTHER_WARD_ID, SosTriggerType.GUARDIAN_CALL); // 범위 밖 - 세지 않는다

        Map<SosTriggerType, Long> counts = sosEventRepository.countByTriggerType(List.of(WARD_ID)).stream()
                .collect(Collectors.toMap(SosEventRepository.TriggerTypeCount::getTriggerType,
                        SosEventRepository.TriggerTypeCount::getTotal));
        assertThat(counts).containsOnly(
                Map.entry(SosTriggerType.SOS_BUTTON, 3L),
                Map.entry(SosTriggerType.GUARDIAN_CALL, 1L));

        // 페이지 크기 1이어도 필터 기준 전체 건수가 나온다
        var page = sosEventRepository.findByWardIdInAndTriggerTypeOrderByCreatedAtDesc(
                List.of(WARD_ID), SosTriggerType.SOS_BUTTON, PageRequest.of(0, 1));
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(SosEvent::getTriggerType)
                .containsOnly(SosTriggerType.SOS_BUTTON);
    }

    private void save(String wardId, SosTriggerType triggerType) {
        sosEventRepository.saveAndFlush(SosEvent.builder()
                .wardId(wardId)
                .location("거실")
                .triggerType(triggerType)
                .build());
    }
}

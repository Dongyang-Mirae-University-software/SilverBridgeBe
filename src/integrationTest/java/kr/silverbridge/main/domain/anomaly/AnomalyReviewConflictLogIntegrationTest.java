package kr.silverbridge.main.domain.anomaly;

import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyReviewConflictLogRepository;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.DetectedType;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동수 재확인 안내 기록의 원자적 insert-if-absent(2026-09-21, PR #252 E-1).
 *
 * <p>보호자 응답이 안내 기록 때문에 실패하면 안 된다 - 조회 후 저장 대신 {@code ON CONFLICT DO NOTHING}으로 바꾼
 * 이유다. 이 동작은 PostgreSQL 문법이라 목으로는 검증되지 않았다.</p>
 */
class AnomalyReviewConflictLogIntegrationTest extends PostgresIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AnomalyIncidentRepository incidentRepository;
    @Autowired private AnomalyReviewConflictLogRepository conflictLogRepository;

    @Test
    @DisplayName("같은 (상황, 보호자)로 두 번 넣어도 예외 없이 1행만 남는다 - 두 번째는 0을 돌려준다")
    void 중복_insert는_무시된다() {
        userRepository.save(TestData.user("WD0001", "김영희", Role.WARD));
        userRepository.save(TestData.user("GD0001", "박보호", Role.GUARDIAN));
        AnomalyIncident incident = incidentRepository.save(AnomalyIncident.builder()
                .wardId("WD0001")
                .sessionId("sess-living")
                .detectedType(DetectedType.FIRE)
                .detectedAt(OffsetDateTime.of(2026, 9, 21, 9, 0, 0, 0, ZoneOffset.ofHours(9)))
                .confidence(0.8)
                .build());
        OffsetDateTime now = OffsetDateTime.now();

        int first = conflictLogRepository.insertSkipIfAbsent(incident.getId(), "GD0001", now);
        int second = conflictLogRepository.insertSkipIfAbsent(incident.getId(), "GD0001", now);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(conflictLogRepository.findByIncidentIdIn(List.of(incident.getId())))
                .singleElement()
                .satisfies(log -> assertThat(log.isSent()).isFalse());
    }
}

package kr.silverbridge.main.domain.anomaly;

import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewReminderLog;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyReviewReminderLogRepository;
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
 * 요약 미루기(ANOM-G09) 판정 쿼리 - 보호자 목록 + 발송 시각 하한으로 최근 건별 재촉을 찾는다.
 *
 * <p>파생 쿼리라 목으로는 컬럼 매핑·{@code timestamptz} 비교가 검증되지 않는다.</p>
 */
class AnomalyReviewReminderLogQueryIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    @Autowired private UserRepository userRepository;
    @Autowired private AnomalyIncidentRepository incidentRepository;
    @Autowired private AnomalyReviewReminderLogRepository reminderLogRepository;

    @Test
    @DisplayName("지정한 보호자의 하한 이후 재촉만 돌려준다 - 하한과 같은 시각·다른 보호자는 빠진다")
    void 보호자와_시각으로_최근_재촉을_찾는다() {
        userRepository.save(TestData.user("WD0001", "김영희", Role.WARD));
        userRepository.save(TestData.user("GD0001", "박보호", Role.GUARDIAN));
        userRepository.save(TestData.user("GD0002", "이보호", Role.GUARDIAN));
        AnomalyIncident incident = incidentRepository.save(AnomalyIncident.builder()
                .wardId("WD0001")
                .sessionId("sess-living")
                .detectedType(DetectedType.FIRE)
                .detectedAt(OffsetDateTime.of(2026, 10, 5, 18, 0, 0, 0, KST))
                .confidence(0.8)
                .build());
        OffsetDateTime after = OffsetDateTime.of(2026, 10, 5, 18, 30, 0, 0, KST);

        reminderLogRepository.saveAll(List.of(
                AnomalyReviewReminderLog.builder().incidentId(incident.getId()).guardianId("GD0001")
                        .sentAt(after.plusMinutes(35)).build(),
                AnomalyReviewReminderLog.builder().incidentId(incident.getId()).guardianId("GD0002")
                        .sentAt(after.plusMinutes(35)).build()));

        // UTC로 넘겨도 같은 순간이면 같은 결과다
        assertThat(reminderLogRepository.findByGuardianIdInAndSentAtAfter(
                List.of("GD0001"), after.withOffsetSameInstant(ZoneOffset.UTC)))
                .singleElement()
                .satisfies(log -> assertThat(log.getGuardianId()).isEqualTo("GD0001"));
        assertThat(reminderLogRepository.findByGuardianIdInAndSentAtAfter(
                List.of("GD0001"), after.plusMinutes(35))).isEmpty();
    }
}

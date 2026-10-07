package kr.silverbridge.main.domain.anomaly;

import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.DetectedType;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보호자 이력 유형 필터·요약 쿼리(2026-10-07)를 실제 PostgreSQL에서 실행한다.
 * enum null 조건(`:type IS NULL OR ...`)·IN 절·GROUP BY 프로젝션·보조 정렬을 본다.
 */
class GuardianAnomalyHistoryQueryIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final List<String> WARD_1 = List.of("WD0001");
    private static final List<String> BOTH = List.of("WD0001", "WD0002");

    @Autowired private AnomalyIncidentRepository incidentRepository;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user("WD0001", "김영희", Role.WARD));
        userRepository.save(TestData.user("WD0002", "이철수", Role.WARD));
        // WD0001: 화재 3(PENDING·REAL·CONFLICTED), 낙상 1(FALSE_ALARM) / WD0002: 화재 1, 흉기 1(다른 피보호자)
        incident("WD0001", DetectedType.FIRE, 1, AnomalyReviewStatus.PENDING);
        incident("WD0001", DetectedType.FIRE, 2, AnomalyReviewStatus.REAL);
        incident("WD0001", DetectedType.FIRE, 3, AnomalyReviewStatus.CONFLICTED);
        incident("WD0001", DetectedType.FALL, 4, AnomalyReviewStatus.FALSE_ALARM);
        incident("WD0002", DetectedType.FIRE, 5, AnomalyReviewStatus.PENDING);
        incident("WD0002", DetectedType.WEAPON, 6, AnomalyReviewStatus.PENDING);
    }

    @Test
    @DisplayName("type null이면 범위 전체, 값이 있으면 그 유형만 - 다른 피보호자는 섞이지 않는다")
    void filterAndScope() {
        assertThat(incidentRepository.findHistory(WARD_1, null, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(4);
        assertThat(incidentRepository.findHistory(WARD_1, DetectedType.FIRE, PageRequest.of(0, 20)).getTotalElements())
                .isEqualTo(3);
        assertThat(incidentRepository.findHistory(WARD_1, DetectedType.WEAPON, PageRequest.of(0, 20)).getTotalElements())
                .isZero();
        assertThat(incidentRepository.findHistory(BOTH, DetectedType.FIRE, PageRequest.of(0, 20)).getTotalElements())
                .isEqualTo(4);
    }

    @Test
    @DisplayName("페이지 경계가 필터 기준이고, 최신순(startedAt DESC)이다")
    void pagingUnderFilter() {
        Page<AnomalyIncident> first = incidentRepository.findHistory(WARD_1, DetectedType.FIRE, PageRequest.of(0, 2));
        Page<AnomalyIncident> second = incidentRepository.findHistory(WARD_1, DetectedType.FIRE, PageRequest.of(1, 2));

        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).hasSize(2);
        assertThat(second.getContent()).hasSize(1);
        assertThat(first.getContent().get(0).getStartedAt()).isAfter(first.getContent().get(1).getStartedAt());
        assertThat(first.getContent()).doesNotContainAnyElementsOf(second.getContent());
    }

    @Test
    @DisplayName("요약 집계는 목록 totalElements와 같은 범위·같은 수를 센다")
    void summaryMatchesList() {
        List<AnomalyIncidentRepository.GuardianTypeStatusCount> rows = incidentRepository.countForGuardianSummary(WARD_1);

        long total = rows.stream().mapToLong(AnomalyIncidentRepository.GuardianTypeStatusCount::getTotal).sum();
        assertThat(total).isEqualTo(incidentRepository.findHistory(WARD_1, null, PageRequest.of(0, 20)).getTotalElements());

        Map<String, Long> byKey = rows.stream().collect(Collectors.toMap(
                row -> row.getDetectedType() + "/" + row.getReviewStatus(),
                AnomalyIncidentRepository.GuardianTypeStatusCount::getTotal));
        assertThat(byKey).containsEntry("FIRE/PENDING", 1L).containsEntry("FIRE/CONFLICTED", 1L)
                .containsEntry("FALL/FALSE_ALARM", 1L).doesNotContainKey("WEAPON/PENDING");
    }

    private void incident(String wardId, DetectedType type, int hour, AnomalyReviewStatus status) {
        AnomalyIncident incident = AnomalyIncident.builder()
                .wardId(wardId)
                .sessionId("sess-" + wardId)
                .detectedType(type)
                .detectedAt(OffsetDateTime.of(2026, 10, 6, hour, 0, 0, 0, KST))
                .confidence(0.8)
                .build();
        incident.applyReviewStatus(status);
        incidentRepository.save(incident);
    }
}

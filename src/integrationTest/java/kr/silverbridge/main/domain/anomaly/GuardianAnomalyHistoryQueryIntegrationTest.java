package kr.silverbridge.main.domain.anomaly;

import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.camera.entity.Camera;
import kr.silverbridge.main.domain.camera.repository.CameraRepository;
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
 * 보호자 이력 유형 필터·요약 쿼리(2026-10-07)와 삭제된 카메라 필터(2026-10-24)를 실제 PostgreSQL에서 실행한다.
 * enum null 조건(`:type IS NULL OR ...`)·IN/NOT IN 절·boolean 조건·GROUP BY 프로젝션·보조 정렬을 본다.
 */
class GuardianAnomalyHistoryQueryIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final List<String> WARD_1 = List.of("WD0001");
    private static final List<String> BOTH = List.of("WD0001", "WD0002");
    /** 서비스가 빈 IN 절 대신 넣는 매칭 불가 값(카메라 필터를 쓰지 않으면 목록은 무시된다). */
    private static final List<String> NO_MATCH = List.of("");
    private static final String VIEWER = "GAH009";

    @Autowired private AnomalyIncidentRepository incidentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private kr.silverbridge.main.domain.connection.repository.ConnectionRepository connectionRepository;
    @Autowired private CameraRepository cameraRepository;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user("WD0001", "김영희", Role.WARD));
        userRepository.save(TestData.user("WD0002", "이철수", Role.WARD));
        // 보호자용 쿼리는 "이 보호자의 ACTIVE 연결 이후" 기록만 센다(2026-10-31 D-1) - 기존 데이터보다 앞서 연결해 둔다
        userRepository.save(TestData.user(VIEWER, "보는보호자", Role.GUARDIAN));
        connectionRepository.save(TestData.activeConnection(VIEWER, "WD0001", java.time.OffsetDateTime.parse("2000-01-01T00:00:00Z")));
        connectionRepository.save(TestData.activeConnection(VIEWER, "WD0002", java.time.OffsetDateTime.parse("2000-01-01T00:00:00Z")));
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
        assertThat(incidentRepository.findHistory(VIEWER, WARD_1, null, false, false, NO_MATCH, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(4);
        assertThat(incidentRepository.findHistory(VIEWER, WARD_1, DetectedType.FIRE, false, false, NO_MATCH, PageRequest.of(0, 20)).getTotalElements())
                .isEqualTo(3);
        assertThat(incidentRepository.findHistory(VIEWER, WARD_1, DetectedType.WEAPON, false, false, NO_MATCH, PageRequest.of(0, 20)).getTotalElements())
                .isZero();
        assertThat(incidentRepository.findHistory(VIEWER, BOTH, DetectedType.FIRE, false, false, NO_MATCH, PageRequest.of(0, 20)).getTotalElements())
                .isEqualTo(4);
    }

    @Test
    @DisplayName("페이지 경계가 필터 기준이고, 최신순(startedAt DESC)이다")
    void pagingUnderFilter() {
        Page<AnomalyIncident> first = incidentRepository.findHistory(VIEWER, WARD_1, DetectedType.FIRE, false, false, NO_MATCH, PageRequest.of(0, 2));
        Page<AnomalyIncident> second = incidentRepository.findHistory(VIEWER, WARD_1, DetectedType.FIRE, false, false, NO_MATCH, PageRequest.of(1, 2));

        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).hasSize(2);
        assertThat(second.getContent()).hasSize(1);
        assertThat(first.getContent().get(0).getStartedAt()).isAfter(first.getContent().get(1).getStartedAt());
        assertThat(first.getContent()).doesNotContainAnyElementsOf(second.getContent());
    }

    @Test
    @DisplayName("요약 집계는 camera=EXISTING 목록 totalElements와 같은 범위·같은 수를 센다")
    void summaryMatchesList() {
        cameraRepository.save(TestData.camera("WD0001", "sess-WD0001", "거실"));
        List<String> live = cameraRepository.findSessionIdsByWardIdIn(WARD_1);
        List<AnomalyIncidentRepository.GuardianTypeStatusCount> rows = incidentRepository.countForGuardianSummary(VIEWER, WARD_1, live);

        long total = rows.stream().mapToLong(AnomalyIncidentRepository.GuardianTypeStatusCount::getTotal).sum();
        assertThat(total).isEqualTo(incidentRepository.findHistory(VIEWER, WARD_1, null, false, true, live, PageRequest.of(0, 20)).getTotalElements());

        Map<String, Long> byKey = rows.stream().collect(Collectors.toMap(
                row -> row.getDetectedType() + "/" + row.getReviewStatus(),
                AnomalyIncidentRepository.GuardianTypeStatusCount::getTotal));
        assertThat(byKey).containsEntry("FIRE/PENDING", 1L).containsEntry("FIRE/CONFLICTED", 1L)
                .containsEntry("FALL/FALSE_ALARM", 1L).doesNotContainKey("WEAPON/PENDING");
    }

    @Test
    @DisplayName("삭제된 카메라 필터: 지금 등록된 카메라 세션에 없는 상황만 / 있는 상황만, 건수는 목록과 같고 다른 피보호자는 섞이지 않는다")
    void deletedCameraFilter() {
        // WD0001의 기존 4건은 sess-WD0001(등록된 카메라). 삭제된 카메라 sess-gone에서 화재 1·낙상 1, WD0002도 sess-gone2에서 1건
        incident("WD0001", "sess-gone", DetectedType.FIRE, 7, AnomalyReviewStatus.PENDING);
        incident("WD0001", "sess-gone", DetectedType.FALL, 8, AnomalyReviewStatus.REAL);
        incident("WD0002", "sess-gone2", DetectedType.FIRE, 9, AnomalyReviewStatus.PENDING);
        cameraRepository.save(TestData.camera("WD0001", "sess-WD0001", "거실"));
        cameraRepository.save(TestData.camera("WD0002", "sess-WD0002", "거실"));

        List<String> live = cameraRepository.findSessionIdsByWardIdIn(WARD_1);
        assertThat(live).containsExactly("sess-WD0001");

        Page<AnomalyIncident> deleted = incidentRepository.findHistory(VIEWER, WARD_1, null, true, false, live, PageRequest.of(0, 20));
        assertThat(deleted.getContent()).extracting(AnomalyIncident::getSessionId).containsOnly("sess-gone");
        assertThat(deleted.getTotalElements()).isEqualTo(2)
                .isEqualTo(incidentRepository.countDeletedCameraForGuardian(VIEWER, WARD_1, live));
        assertThat(incidentRepository.findHistory(VIEWER, WARD_1, DetectedType.FALL, true, false, live, PageRequest.of(0, 20))
                .getTotalElements()).isEqualTo(1);
        assertThat(incidentRepository.findHistory(VIEWER, WARD_1, null, false, true, live, PageRequest.of(0, 20))
                .getTotalElements()).isEqualTo(4);
        // 등록된 카메라가 하나도 없으면(매칭 불가 값) 전부 삭제된 카메라다
        assertThat(incidentRepository.countDeletedCameraForGuardian(VIEWER, WARD_1, NO_MATCH)).isEqualTo(6);
        // 두 피보호자 범위
        List<String> liveBoth = cameraRepository.findSessionIdsByWardIdIn(BOTH);
        assertThat(incidentRepository.countDeletedCameraForGuardian(VIEWER, BOTH, liveBoth)).isEqualTo(3);
    }

    @Test
    @DisplayName("요약은 지금 등록된 카메라의 상황만 센다 - 삭제된 카메라 상황은 유형·판정 집계에서 빠지고 deleted 건수로만, 합은 전체 이력과 같다 (2026-10-25)")
    void summaryExcludesDeletedCamera() {
        // 기존 4건(sess-WD0001: 화재 PENDING·REAL·CONFLICTED, 낙상 FALSE_ALARM) + 삭제된 카메라 3건(화재 PENDING, 흉기 CONFLICTED, 낙상 REAL)
        incident("WD0001", "sess-gone", DetectedType.FIRE, 7, AnomalyReviewStatus.PENDING);
        incident("WD0001", "sess-gone", DetectedType.WEAPON, 8, AnomalyReviewStatus.CONFLICTED);
        incident("WD0001", "sess-gone", DetectedType.FALL, 9, AnomalyReviewStatus.REAL);
        // 사용 중지 카메라는 삭제가 아니다 - 그 상황은 그대로 센다
        incident("WD0001", "sess-off", DetectedType.FIRE, 10, AnomalyReviewStatus.PENDING);
        cameraRepository.save(TestData.camera("WD0001", "sess-WD0001", "거실"));
        Camera off = TestData.camera("WD0001", "sess-off", "침실");
        off.deactivate();
        cameraRepository.save(off);
        List<String> live = cameraRepository.findSessionIdsByWardIdIn(WARD_1);

        Map<String, Long> byKey = incidentRepository.countForGuardianSummary(VIEWER, WARD_1, live).stream()
                .collect(Collectors.toMap(row -> row.getDetectedType() + "/" + row.getReviewStatus(),
                        AnomalyIncidentRepository.GuardianTypeStatusCount::getTotal));
        assertThat(byKey).containsOnly(
                Map.entry("FIRE/PENDING", 2L), Map.entry("FIRE/REAL", 1L), Map.entry("FIRE/CONFLICTED", 1L),
                Map.entry("FALL/FALSE_ALARM", 1L));

        long existing = byKey.values().stream().mapToLong(Long::longValue).sum();
        long deleted = incidentRepository.countDeletedCameraForGuardian(VIEWER, WARD_1, live);
        assertThat(existing).isEqualTo(5)
                .isEqualTo(incidentRepository.findHistory(VIEWER, WARD_1, null, false, true, live, PageRequest.of(0, 20))
                        .getTotalElements());
        assertThat(deleted).isEqualTo(3);
        assertThat(existing + deleted).isEqualTo(
                incidentRepository.findHistory(VIEWER, WARD_1, null, false, false, live, PageRequest.of(0, 20)).getTotalElements());
        // 등록된 카메라가 하나도 없으면(매칭 불가 값) 기본 집계는 비어 있다
        assertThat(incidentRepository.countForGuardianSummary(VIEWER, WARD_1, NO_MATCH)).isEmpty();
    }

    private void incident(String wardId, DetectedType type, int hour, AnomalyReviewStatus status) {
        incident(wardId, "sess-" + wardId, type, hour, status);
    }

    private void incident(String wardId, String sessionId, DetectedType type, int hour, AnomalyReviewStatus status) {
        AnomalyIncident incident = AnomalyIncident.builder()
                .wardId(wardId)
                .sessionId(sessionId)
                .detectedType(type)
                .detectedAt(OffsetDateTime.of(2026, 10, 6, hour, 0, 0, 0, KST))
                .confidence(0.8)
                .build();
        incident.applyReviewStatus(status);
        incidentRepository.save(incident);
    }
}

package kr.silverbridge.main.domain.connection;

import jakarta.persistence.EntityManager;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.sos.entity.SosEvent;
import kr.silverbridge.main.domain.sos.entity.SosTriggerType;
import kr.silverbridge.main.domain.sos.repository.SosEventRepository;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.DetectedType;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보호자 이력 열람 범위 - <b>지금 ACTIVE인 연결이 맺어진 뒤</b>의 기록만 보인다(2026-10-31 전체 점검 D-1, 사용자 결정).
 *
 * <p>끊었다 다시 맺은 연결은 그 사이·그 이전 기록을 보지 못한다. 기준은 끊긴 옛 연결이 아니라 ACTIVE 행의
 * {@code connected_at}이고, 그 값이 비어 있는 옛 행은 시점을 모르므로 전부 보인다. 쿼리 안의 EXISTS 조건이라
 * 목 테스트로는 검증되지 않는다.</p>
 */
class HistorySinceConnectedIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final String WARD = "WHS001";
    private static final String GUARDIAN = "GHS001";
    private static final String LEGACY_GUARDIAN = "GHS002";
    private static final List<String> WARDS = List.of(WARD);
    /** 첫 연결 10/1, 해제 10/5, 다시 연결 10/10. */
    private static final OffsetDateTime RECONNECTED_AT = OffsetDateTime.of(2026, 10, 10, 9, 0, 0, 0, KST);

    @Autowired private SosEventRepository sosEventRepository;
    @Autowired private AnomalyIncidentRepository incidentRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user(WARD, "피보호자", Role.WARD));
        userRepository.save(TestData.user(GUARDIAN, "다시연결한보호자", Role.GUARDIAN));
        userRepository.save(TestData.user(LEGACY_GUARDIAN, "옛연결보호자", Role.GUARDIAN));
        // 끊긴 옛 연결(10/1 연결) - 기준이 되면 안 된다
        connectionRepository.save(Connection.builder().guardianId(GUARDIAN).wardId(WARD)
                .status(ConnectionStatus.DISCONNECTED).initiatedBy(GUARDIAN)
                .connectedAt(OffsetDateTime.of(2026, 10, 1, 9, 0, 0, 0, KST)).build());
        connectionRepository.save(TestData.activeConnection(GUARDIAN, WARD, RECONNECTED_AT));
        // 연결 시각이 없는 옛 ACTIVE 행
        connectionRepository.save(TestData.activeConnection(LEGACY_GUARDIAN, WARD, null));

        sosAt(OffsetDateTime.of(2026, 10, 2, 9, 0, 0, 0, KST));   // 첫 연결 중 - 보이지 않는다
        sosAt(OffsetDateTime.of(2026, 10, 7, 9, 0, 0, 0, KST));   // 끊긴 동안 - 보이지 않는다
        sosAt(RECONNECTED_AT);                                     // 다시 연결한 순간 - 보인다
        sosAt(OffsetDateTime.of(2026, 10, 12, 9, 0, 0, 0, KST));  // 보인다

        incidentAt(OffsetDateTime.of(2026, 10, 7, 9, 0, 0, 0, KST));     // 끊긴 동안 - 보이지 않는다
        incidentAt(RECONNECTED_AT.minusMinutes(5));                     // 연결 직전 시작 + 직후까지 이어짐 - 보인다
        incidentAt(OffsetDateTime.of(2026, 10, 12, 9, 0, 0, 0, KST));    // 보인다
        entityManager.flush();
    }

    @Test
    @DisplayName("SOS 이력·건수·묶음은 다시 연결한 뒤의 기록만 센다")
    void sos() {
        assertThat(sosEventRepository.findVisibleHistory(GUARDIAN, WARDS, PageRequest.of(0, 20)).getTotalElements())
                .isEqualTo(2);
        assertThat(sosEventRepository.countVisibleSince(GUARDIAN, WARDS,
                OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, KST))).isEqualTo(2);
        assertThat(sosEventRepository.countVisibleByTriggerType(GUARDIAN, WARDS))
                .singleElement().satisfies(row -> assertThat(row.getTotal()).isEqualTo(2));
        assertThat(sosEventRepository.countPressGroups(GUARDIAN, WARDS, "", 60)).isEqualTo(2);
    }

    @Test
    @DisplayName("이상감지 이력·요약은 다시 연결한 뒤에도 감지가 이어진 상황만 센다")
    void anomaly() {
        List<String> live = List.of("sess-" + WARD);
        assertThat(incidentRepository.findHistory(GUARDIAN, WARDS, null, false, false, List.of(""),
                PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);
        assertThat(incidentRepository.countForGuardianSummary(GUARDIAN, WARDS, live))
                .singleElement().satisfies(row -> assertThat(row.getTotal()).isEqualTo(2));
    }

    @Test
    @DisplayName("연결 시각이 없는 옛 ACTIVE 연결은 전부 보이고, 연결이 없는 보호자는 아무것도 보지 못한다")
    void legacyAndUnconnected() {
        assertThat(sosEventRepository.findVisibleHistory(LEGACY_GUARDIAN, WARDS, PageRequest.of(0, 20))
                .getTotalElements()).isEqualTo(4);
        assertThat(incidentRepository.findHistory(LEGACY_GUARDIAN, WARDS, null, false, false, List.of(""),
                PageRequest.of(0, 20)).getTotalElements()).isEqualTo(3);
        // wardIds 인가를 우회해도 쿼리 안에서 한 번 더 막힌다
        assertThat(sosEventRepository.findVisibleHistory("GHS999", WARDS, PageRequest.of(0, 20)).getTotalElements())
                .isZero();
    }

    /** {@code created_at}은 감사 필드(수정 불가 매핑)라 저장 후 네이티브 UPDATE로 시각을 옮긴다. */
    private void sosAt(OffsetDateTime createdAt) {
        Long id = sosEventRepository.saveAndFlush(SosEvent.builder()
                .wardId(WARD).location("거실").triggerType(SosTriggerType.SOS_BUTTON).build()).getId();
        entityManager.createNativeQuery("UPDATE sos_event SET created_at = :createdAt WHERE id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", id)
                .executeUpdate();
    }

    /** 감지 시작 시각 + 10분 동안 이어진 상황(마지막 감지 = 시작 + 10분). */
    private void incidentAt(OffsetDateTime startedAt) {
        AnomalyIncident incident = AnomalyIncident.builder()
                .wardId(WARD)
                .sessionId("sess-" + WARD)
                .detectedType(DetectedType.FIRE)
                .detectedAt(startedAt)
                .confidence(0.8)
                .build();
        incident.applyReviewStatus(AnomalyReviewStatus.PENDING);
        Long id = incidentRepository.saveAndFlush(incident).getId();
        entityManager.createNativeQuery("UPDATE anomaly_incident SET last_detected_at = :last WHERE id = :id")
                .setParameter("last", startedAt.plusMinutes(10))
                .setParameter("id", id)
                .executeUpdate();
    }
}

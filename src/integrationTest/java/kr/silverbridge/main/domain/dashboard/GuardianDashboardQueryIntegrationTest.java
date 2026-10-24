package kr.silverbridge.main.domain.dashboard;

import jakarta.persistence.EntityManager;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.sos.entity.SosEvent;
import kr.silverbridge.main.domain.sos.repository.SosEventRepository;
import kr.silverbridge.main.domain.user.repository.UserRepository;
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
 * 보호자 대시보드용 신규 쿼리를 실제 PostgreSQL에서 실행한다 - 확인 필요 최신 1건, SOS 이번 달(KST) 건수.
 * 파생 쿼리·IN 절·TIMESTAMPTZ 경계는 목 테스트가 실행하지 않는다.
 */
class GuardianDashboardQueryIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final List<AnomalyReviewStatus> NEEDS_REVIEW =
            List.of(AnomalyReviewStatus.PENDING, AnomalyReviewStatus.CONFLICTED);
    /** 지금 등록된 카메라 세션(서비스가 카메라 도메인에서 받아 넘기는 값). 이 쿼리는 카메라 행이 아니라 이 목록만 본다. */
    private static final List<String> LIVE = List.of("sess-WD0001", "sess-WD0002");

    @Autowired private AnomalyIncidentRepository incidentRepository;
    @Autowired private SosEventRepository sosEventRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user("WD0001", "김영희", Role.WARD));
        userRepository.save(TestData.user("WD0002", "이순자", Role.WARD));
    }

    @Test
    @DisplayName("확인 필요 최신 1건: PENDING·CONFLICTED만, 최신순, 인가 밖 피보호자는 섞이지 않고, 개수는 요약과 같은 기준")
    void latestNeedsReview() {
        incident("WD0001", 1, AnomalyReviewStatus.PENDING);
        incident("WD0001", 2, AnomalyReviewStatus.REAL);           // 확인 필요 아님
        incident("WD0001", 3, AnomalyReviewStatus.CONFLICTED);     // 가장 최근 확인 필요
        incident("WD0001", 4, AnomalyReviewStatus.FALSE_ALARM);    // 더 최근이지만 확인 필요 아님
        incident("WD0002", 5, AnomalyReviewStatus.PENDING);        // 다른 피보호자

        List<AnomalyIncident> latest = incidentRepository.findLatestByStatuses(
                List.of("WD0001"), LIVE, NEEDS_REVIEW, PageRequest.of(0, 1));
        assertThat(latest).hasSize(1);
        assertThat(latest.get(0).getReviewStatus()).isEqualTo(AnomalyReviewStatus.CONFLICTED);
        assertThat(latest.get(0).getWardId()).isEqualTo("WD0001");

        assertThat(incidentRepository.findLatestByStatuses(List.of("WD0001", "WD0002"), LIVE, NEEDS_REVIEW,
                PageRequest.of(0, 1)).get(0).getReviewStatus()).isEqualTo(AnomalyReviewStatus.PENDING); // WD0002 05시

        assertThat(needsReviewCount(List.of("WD0001"), LIVE)).isEqualTo(2);
    }

    @Test
    @DisplayName("확인 필요 건수·최신 1건은 삭제된 카메라의 상황을 빼고, 둘이 같은 기준이다 (2026-10-25)")
    void deletedCameraExcluded() {
        incident("WD0001", "sess-WD0001", 1, AnomalyReviewStatus.PENDING);     // 등록된 카메라
        incident("WD0001", "sess-gone", 2, AnomalyReviewStatus.CONFLICTED);    // 삭제된 카메라 - 더 최근이지만 빠진다
        incident("WD0001", "sess-gone", 3, AnomalyReviewStatus.PENDING);       // 삭제된 카메라

        assertThat(needsReviewCount(List.of("WD0001"), LIVE)).isEqualTo(1);
        List<AnomalyIncident> latest = incidentRepository.findLatestByStatuses(
                List.of("WD0001"), LIVE, NEEDS_REVIEW, PageRequest.of(0, 1));
        assertThat(latest).singleElement().extracting(AnomalyIncident::getSessionId).isEqualTo("sess-WD0001");

        // 등록된 카메라가 하나도 없으면(매칭 불가 값) 확인 필요는 0건·최신 1건 없음
        assertThat(needsReviewCount(List.of("WD0001"), List.of(""))).isZero();
        assertThat(incidentRepository.findLatestByStatuses(List.of("WD0001"), List.of(""), NEEDS_REVIEW,
                PageRequest.of(0, 1))).isEmpty();
    }

    @Test
    @DisplayName("확인 필요 상황이 없으면 빈 목록")
    void latestNeedsReviewEmpty() {
        incident("WD0001", 1, AnomalyReviewStatus.REAL);
        assertThat(incidentRepository.findLatestByStatuses(List.of("WD0001"), LIVE, NEEDS_REVIEW, PageRequest.of(0, 1)))
                .isEmpty();
    }

    private long needsReviewCount(List<String> wardIds, List<String> liveSessionIds) {
        return incidentRepository.countForGuardianSummary(wardIds, liveSessionIds).stream()
                .filter(r -> r.getReviewStatus() == AnomalyReviewStatus.PENDING
                        || r.getReviewStatus() == AnomalyReviewStatus.CONFLICTED)
                .mapToLong(AnomalyIncidentRepository.GuardianTypeStatusCount::getTotal).sum();
    }

    @Test
    @DisplayName("SOS 이번 달(KST) 건수: 말일 23:59:59는 지난달, 1일 00:00:00은 이번 달, 다른 피보호자는 세지 않는다")
    void sosMonthBoundaryKst() {
        OffsetDateTime monthStart = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, KST);

        sosAt("WD0001", OffsetDateTime.of(2026, 9, 30, 23, 59, 59, 0, KST));  // 지난달
        sosAt("WD0001", monthStart);                                           // 경계 - 센다
        sosAt("WD0001", OffsetDateTime.of(2026, 10, 7, 9, 0, 0, 0, KST));      // 센다
        sosAt("WD0002", OffsetDateTime.of(2026, 10, 7, 9, 0, 0, 0, KST));      // 인가 밖

        assertThat(sosEventRepository.countByWardIdInAndCreatedAtGreaterThanEqual(List.of("WD0001"), monthStart))
                .isEqualTo(2);
        assertThat(sosEventRepository.countByWardIdInAndCreatedAtGreaterThanEqual(
                List.of("WD0001", "WD0002"), monthStart)).isEqualTo(3);
        // UTC로 표현한 같은 순간이어도 결과는 같다(오프셋이 아니라 순간으로 비교)
        assertThat(sosEventRepository.countByWardIdInAndCreatedAtGreaterThanEqual(
                List.of("WD0001"), monthStart.withOffsetSameInstant(ZoneOffset.UTC))).isEqualTo(2);
    }

    private void incident(String wardId, int hour, AnomalyReviewStatus status) {
        incident(wardId, "sess-" + wardId, hour, status);
    }

    private void incident(String wardId, String sessionId, int hour, AnomalyReviewStatus status) {
        AnomalyIncident incident = AnomalyIncident.builder()
                .wardId(wardId)
                .sessionId(sessionId)
                .detectedType(DetectedType.FIRE)
                .detectedAt(OffsetDateTime.of(2026, 10, 6, hour, 0, 0, 0, KST))
                .confidence(0.8)
                .build();
        incident.applyReviewStatus(status);
        incidentRepository.save(incident);
    }

    /** {@code created_at}은 감사 필드라 저장 후 네이티브 UPDATE로 옮긴다. */
    private void sosAt(String wardId, OffsetDateTime createdAt) {
        Long id = sosEventRepository.saveAndFlush(SosEvent.builder().wardId(wardId).location("침실").build()).getId();
        entityManager.createNativeQuery("UPDATE sos_event SET created_at = :createdAt WHERE id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", id)
                .executeUpdate();
        entityManager.clear();
    }
}

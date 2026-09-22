package kr.silverbridge.main.domain.anomaly;

import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
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
import static org.assertj.core.api.Assertions.within;

/**
 * 관리자 이상감지 로그 v2 쿼리(2026-09-21, PR #253)를 실제 PostgreSQL에서 실행한다.
 *
 * <p>단위 테스트는 리포지토리를 목으로 바꿔 서비스 로직만 봤다. 여기서는 JPQL 자체 - 불린 파라미터 비교,
 * IN 절 두 개, enum null 조건, GROUP BY 인터페이스 프로젝션(SUM 포함), LIKE {@code escape '\'} - 가 PostgreSQL에서
 * 기대대로 동작하는지를 본다.</p>
 */
class AdminAnomalyQueryIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final OffsetDateTime NO_LOWER_BOUND = OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final List<String> NO_MATCH = List.of("");
    private static final PageRequest PAGE = PageRequest.of(0, 20);

    @Autowired private AnomalyIncidentRepository incidentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CameraRepository cameraRepository;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user("WD0001", "김영희", Role.WARD));
        userRepository.save(TestData.user("WD0002", "50%_할인", Role.WARD));
        cameraRepository.save(TestData.camera("WD0001", "sess-living", "거실"));
        cameraRepository.save(TestData.camera("WD0002", "sess-kitchen", "주방"));

        // 9/15(지난주) 화재 REAL, 9/21(이번 주) 화재 FALSE_ALARM, 9/21 낙상 PENDING, 9/22 화재 CONFLICTED
        incident("WD0001", "sess-living", DetectedType.FIRE, kst(9, 15, 10), AnomalyReviewStatus.REAL);
        incident("WD0001", "sess-living", DetectedType.FIRE, kst(9, 21, 9), AnomalyReviewStatus.FALSE_ALARM);
        incident("WD0002", "sess-kitchen", DetectedType.FALL, kst(9, 21, 12), AnomalyReviewStatus.PENDING);
        incident("WD0002", "sess-kitchen", DetectedType.FIRE, kst(9, 22, 8), AnomalyReviewStatus.CONFLICTED);
    }

    @Test
    @DisplayName("조건을 모두 생략하면(하한 2000-01-01) 전체가 최신순으로 나온다 - 하위호환")
    void 전체_조회() {
        Page<AnomalyIncident> page = search(null, NO_LOWER_BOUND, null, false, NO_MATCH, NO_MATCH);

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent()).extracting(AnomalyIncident::getStartedAt)
                .isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    @DisplayName("기간 하한·유형·판정 상태 조건이 함께 걸린다")
    void 기간_유형_상태() {
        OffsetDateTime thisWeek = kst(9, 21, 0);

        assertThat(search(null, thisWeek, null, false, NO_MATCH, NO_MATCH).getTotalElements()).isEqualTo(3);
        assertThat(search(null, thisWeek, DetectedType.FIRE, false, NO_MATCH, NO_MATCH).getTotalElements()).isEqualTo(2);
        assertThat(search(AnomalyReviewStatus.CONFLICTED, thisWeek, DetectedType.FIRE, false, NO_MATCH, NO_MATCH)
                .getContent()).singleElement()
                .extracting(AnomalyIncident::getWardId).isEqualTo("WD0002");
    }

    @Test
    @DisplayName("검색어는 피보호자 ID·카메라 sessionId 목록으로 걸리고, 한쪽이 비면 매칭 불가 값으로 채워도 동작한다")
    void 검색어_목록() {
        // 이름 "김영희"로 찾은 경우 - 카메라 쪽은 결과 없음
        assertThat(search(null, NO_LOWER_BOUND, null, true, List.of("WD0001"), NO_MATCH).getTotalElements())
                .isEqualTo(2);
        // 위치 "주방"으로 찾은 경우 - 피보호자 쪽은 결과 없음
        assertThat(search(null, NO_LOWER_BOUND, null, true, NO_MATCH, List.of("sess-kitchen")).getTotalElements())
                .isEqualTo(2);
        // 둘 다 없음
        assertThat(search(null, NO_LOWER_BOUND, null, true, NO_MATCH, NO_MATCH).getTotalElements()).isZero();
    }

    @Test
    @DisplayName("이름·위치 LIKE 검색은 %·_를 글자 그대로 찾는다(escape '\\\\')")
    void LIKE_이스케이프() {
        // 호출부(AdminAnomalyService)가 소문자화·이스케이프를 마친 값을 넘긴다
        assertThat(userRepository.findIdsByNameContaining("50\\%\\_")).containsExactly("WD0002");
        assertThat(userRepository.findIdsByNameContaining("영희")).containsExactly("WD0001");
        // 이스케이프하지 않은 % 는 와일드카드라 둘 다 걸린다 - 이스케이프가 실제로 효과가 있다는 대조군
        assertThat(userRepository.findIdsByNameContaining("%")).containsExactlyInAnyOrder("WD0001", "WD0002");
        assertThat(cameraRepository.findSessionIdsByLabelContaining("주")).containsExactly("sess-kitchen");
    }

    @Test
    @DisplayName("집계 쿼리는 (유형, 판정)별 건수를 GROUP BY로 돌려주고 프로젝션 getter가 채워진다")
    void 집계_GROUP_BY() {
        List<AnomalyIncidentRepository.TypeStatusCount> rows =
                incidentRepository.countForAdminSummary(kst(9, 21, 0), false, NO_MATCH, NO_MATCH);

        Map<String, Long> counts = rows.stream().collect(Collectors.toMap(
                row -> row.getDetectedType() + "/" + row.getReviewStatus(),
                AnomalyIncidentRepository.TypeStatusCount::getTotal));
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(
                "FIRE/FALSE_ALARM", 1L,
                "FIRE/CONFLICTED", 1L,
                "FALL/PENDING", 1L));
        // max_confidence 합계도 같은 GROUP BY에서 채워진다(각 상황 0.8 한 건씩)
        assertThat(rows).allSatisfy(row -> assertThat(row.getConfidenceSum()).isCloseTo(0.8, within(1e-9)));
    }

    private Page<AnomalyIncident> search(AnomalyReviewStatus status, OffsetDateTime from, DetectedType type,
                                         boolean keywordApplied, List<String> wardIds, List<String> sessionIds) {
        return incidentRepository.searchForAdmin(status, null, from, type, keywordApplied, wardIds, sessionIds, PAGE);
    }

    private void incident(String wardId, String sessionId, DetectedType type, OffsetDateTime at,
                          AnomalyReviewStatus status) {
        AnomalyIncident incident = AnomalyIncident.builder()
                .wardId(wardId)
                .sessionId(sessionId)
                .detectedType(type)
                .detectedAt(at)
                .confidence(0.8)
                .build();
        incident.applyReviewStatus(status);
        incidentRepository.save(incident);
    }

    private static OffsetDateTime kst(int month, int day, int hour) {
        return OffsetDateTime.of(2026, month, day, hour, 0, 0, 0, KST);
    }
}

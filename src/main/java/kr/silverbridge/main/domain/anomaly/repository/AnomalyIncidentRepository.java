package kr.silverbridge.main.domain.anomaly.repository;

import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.global.enums.DetectedType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AnomalyIncidentRepository extends JpaRepository<AnomalyIncident, Long> {

    /**
     * 상황 승계 판정용 - 같은 카메라·같은 유형의 <b>가장 최근 상황</b> 1건.
     *
     * <p>이 조회는 감지 1건마다 돈다({@code idx_anomaly_incident_merge}가 커버). 승계 여부는
     * {@code AnomalyIncidentService}가 시간 규칙으로 판단하므로 여기서는 조건을 걸지 않는다 -
     * "직전 상황이 무엇이었나"와 "그것을 이어도 되나"는 다른 질문이다.</p>
     */
    Optional<AnomalyIncident> findFirstByWardIdAndSessionIdAndDetectedTypeOrderByLastDetectedAtDesc(
            String wardId, String sessionId, DetectedType detectedType);

    /**
     * 보호자 이력 목록 - 연결된 피보호자들의 상황을 최신순으로. 정렬 기준이 {@code startedAt}인 이유는
     * 통계의 날짜 소속과 목록의 순서가 어긋나지 않게 하기 위함이다(둘 다 "언제 시작된 상황인가"를 본다).
     */
    Page<AnomalyIncident> findByWardIdInOrderByStartedAtDesc(Collection<String> wardIds, Pageable pageable);

    /**
     * 건별 재촉 후보 - 아직 아무도 응답하지 않았고, <b>상황이 닫힌 뒤 유예까지 지났으며</b>, 마감 전인 것.
     *
     * @param closedAndDueBefore {@code now - (묶음 간격 + 유예)} - 이 시각 이전이 마지막 감지면
     *                           상황이 닫히고 유예까지 지난 것이다
     * @param deadlineFrom       {@code now - 마감일}. 상황 <b>시작</b> 기준이다
     */
    List<AnomalyIncident> findByReviewStatusAndLastDetectedAtLessThanEqualAndStartedAtGreaterThanEqual(
            AnomalyReviewStatus reviewStatus, OffsetDateTime closedAndDueBefore, OffsetDateTime deadlineFrom);

    /** 요약 후보 - 마감 전이고 아직 판정되지 않은 상황 전부(1차 재촉 여부는 호출부가 거른다). */
    List<AnomalyIncident> findByReviewStatusAndStartedAtGreaterThanEqual(
            AnomalyReviewStatus reviewStatus, OffsetDateTime deadlineFrom);
}

package kr.silverbridge.main.domain.anomaly.repository;

import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.global.enums.DetectedType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 관리자 대시보드 - 특정 시각 이후 <b>시작된</b> 상황 전부.
     *
     * <p>유형별·판정별 집계를 SQL group by로 나누지 않고 원본을 받아 애플리케이션에서 묶는다.
     * 하루치 상황은 건수가 적고, 무엇보다 <b>0건인 유형은 항목 자체를 만들지 않아야</b> 하는데
     * (0을 보여주면 "안전하다"로 오독된다) group by 결과를 그대로 쓰면 그 규칙이 자연히 지켜진다.</p>
     */
    List<AnomalyIncident> findByStartedAtGreaterThanEqual(OffsetDateTime from);

    /**
     * 관리자 로그 목록 - 판정 상태·피보호자·기간·유형·검색어로 거르고 최신순.
     *
     * <p>보호자 조회와 달리 <b>연결 여부로 좁히지 않는다</b> - 운영 현황은 전체를 봐야 한다.
     * 조회 전용이라 감사 로그는 남기지 않는다.</p>
     *
     * <p>{@code status}·{@code wardId}·{@code type}은 null이면 무시된다(문의 관리자 목록과 같은 동적 필터 방식).
     * 기간은 null 대신 하한 시각을 항상 받는다 - "전체"는 호출부가 충분히 이른 시각을 넘긴다
     * (null 시각 파라미터는 PostgreSQL에서 타입 추론이 흔들린다).</p>
     *
     * <p>검색어는 이 쿼리가 아니라 호출부가 먼저 피보호자 ID·카메라 sessionId 목록으로 바꿔 넘긴다 - 상황 행에는
     * 이름·위치가 없다. {@code keywordApplied=false}면 두 목록은 무시되며, 목록이 비면 호출부가 매칭 불가능한
     * 값 하나를 넣는다(빈 IN 절 회피).</p>
     */
    @Query("""
            SELECT i FROM AnomalyIncident i
            WHERE (:status IS NULL OR i.reviewStatus = :status)
              AND (:wardId IS NULL OR i.wardId = :wardId)
              AND i.startedAt >= :from
              AND (:type IS NULL OR i.detectedType = :type)
              AND (:keywordApplied = false
                   OR i.wardId IN :keywordWardIds
                   OR i.sessionId IN :keywordSessionIds)
            ORDER BY i.startedAt DESC
            """)
    Page<AnomalyIncident> searchForAdmin(@Param("status") AnomalyReviewStatus status,
                                         @Param("wardId") String wardId,
                                         @Param("from") OffsetDateTime from,
                                         @Param("type") DetectedType type,
                                         @Param("keywordApplied") boolean keywordApplied,
                                         @Param("keywordWardIds") Collection<String> keywordWardIds,
                                         @Param("keywordSessionIds") Collection<String> keywordSessionIds,
                                         Pageable pageable);

    /**
     * 관리자 로그 집계 - 기간·검색어 안의 상황을 (유형, 판정 상태)별로 센다.
     *
     * <p>원본 행이 아니라 묶인 건수만 받는다 - "전체" 기간이어도 결과는 (유형 수 × 판정 4값) 행을 넘지 않는다.
     * 유형 필터는 일부러 받지 않는다: 유형 탭의 건수는 탭을 골라도 그대로여야 하므로 호출부가 전체를 받아
     * 탭 건수와 선택 유형의 판정 집계를 나눠 계산한다. 조건 규칙은 {@link #searchForAdmin}과 같다.</p>
     */
    @Query("""
            SELECT i.detectedType AS detectedType, i.reviewStatus AS reviewStatus, COUNT(i) AS total
            FROM AnomalyIncident i
            WHERE i.startedAt >= :from
              AND (:keywordApplied = false
                   OR i.wardId IN :keywordWardIds
                   OR i.sessionId IN :keywordSessionIds)
            GROUP BY i.detectedType, i.reviewStatus
            """)
    List<TypeStatusCount> countForAdminSummary(@Param("from") OffsetDateTime from,
                                               @Param("keywordApplied") boolean keywordApplied,
                                               @Param("keywordWardIds") Collection<String> keywordWardIds,
                                               @Param("keywordSessionIds") Collection<String> keywordSessionIds);

    /** {@link #countForAdminSummary} 한 행. */
    interface TypeStatusCount {
        DetectedType getDetectedType();

        AnomalyReviewStatus getReviewStatus();

        long getTotal();
    }
}

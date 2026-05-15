package kr.silverbridge.main.domain.anomaly.repository;

import kr.silverbridge.main.domain.anomaly.entity.AnomalyEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface AnomalyEventRepository extends JpaRepository<AnomalyEvent, Long> {

    // 날짜 범위만 필터 (전체 조회, 최신 감지순)
    @Query("""
            SELECT ae FROM AnomalyEvent ae
            WHERE (:startDate IS NULL OR ae.detectedAt >= :startDate)
            AND (:endDate IS NULL OR ae.detectedAt <= :endDate)
            ORDER BY ae.detectedAt DESC
            """)
    List<AnomalyEvent> findByDateRange(
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    // wardId 목록 + 날짜 범위 필터 (특정 보호자의 피보호자들, 최신 감지순)
    @Query("""
            SELECT ae FROM AnomalyEvent ae
            WHERE ae.wardId IN :wardIds
            AND (:startDate IS NULL OR ae.detectedAt >= :startDate)
            AND (:endDate IS NULL OR ae.detectedAt <= :endDate)
            ORDER BY ae.detectedAt DESC
            """)
    List<AnomalyEvent> findByWardIdsAndDateRange(
            @Param("wardIds") List<String> wardIds,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    // 특정 보호자에 연결된 wardId 목록 조회 (ACTIVE 연결만)
    @Query("""
            SELECT c.wardId FROM Connection c
            WHERE c.guardianId = :guardianId
            AND c.status = kr.silverbridge.main.global.enums.ConnectionStatus.ACTIVE
            """)
    List<String> findActiveWardIdsByGuardianId(@Param("guardianId") String guardianId);

    // 관리자 대시보드 — 오늘/어제/누적 이상감지 건수 단일 쿼리로 집계
    @Query(value = """
            SELECT
                COUNT(*) FILTER (WHERE detected_at >= :todayStart AND detected_at < :tomorrowStart)  AS todayCount,
                COUNT(*) FILTER (WHERE detected_at >= :yesterdayStart AND detected_at < :todayStart) AS yesterdayCount,
                COUNT(*)                                                                              AS totalCount
            FROM anomaly_events
            """, nativeQuery = true)
    AnomalyStatsProjection countAnomalyStats(
            @Param("todayStart") OffsetDateTime todayStart,
            @Param("tomorrowStart") OffsetDateTime tomorrowStart,
            @Param("yesterdayStart") OffsetDateTime yesterdayStart);

    interface AnomalyStatsProjection {
        long getTodayCount();
        long getYesterdayCount();
        long getTotalCount();
    }
}

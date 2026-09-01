package kr.silverbridge.main.domain.anomaly.repository;

import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewSummaryLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface AnomalyReviewSummaryLogRepository extends JpaRepository<AnomalyReviewSummaryLog, Long> {

    /** 오늘 이미 요약을 받은 보호자들. 하루 1건 보장의 조회 쪽 절반이다(최종 방어선은 UNIQUE). */
    List<AnomalyReviewSummaryLog> findBySummaryDateAndGuardianIdIn(LocalDate summaryDate, Collection<String> guardianIds);
}

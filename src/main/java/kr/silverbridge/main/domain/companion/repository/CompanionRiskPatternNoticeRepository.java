package kr.silverbridge.main.domain.companion.repository;

import kr.silverbridge.main.domain.companion.entity.CompanionRiskPatternNotice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public interface CompanionRiskPatternNoticeRepository extends JpaRepository<CompanionRiskPatternNotice, String> {

    /**
     * 여러 날 위험 누적 알림 선점(2026-11-11). 처음이거나 마지막 발송이 {@code cutoff} 이전(포함)이면 오늘로 바꾸고 1,
     * 아니면 0. 한 문장(INSERT ... ON CONFLICT DO UPDATE ... WHERE)이라 동시 턴·자정 경계에도 이긴 쪽 하나만 1을 받는다.
     */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO companion_risk_pattern_notice (ward_id, last_notified_date, days, sent_at)
            VALUES (:wardId, :today, :days, :now)
            ON CONFLICT (ward_id) DO UPDATE
                SET last_notified_date = EXCLUDED.last_notified_date, days = EXCLUDED.days, sent_at = EXCLUDED.sent_at
                WHERE companion_risk_pattern_notice.last_notified_date <= :cutoff
            """, nativeQuery = true)
    int claim(@Param("wardId") String wardId, @Param("today") LocalDate today, @Param("days") int days,
              @Param("now") OffsetDateTime now, @Param("cutoff") LocalDate cutoff);

    long countByLastNotifiedDate(LocalDate lastNotifiedDate);

    /** 역할 변경(피보호자 → 보호자) 때 함께 지운다(말벗 기록 정리와 같은 트랜잭션). 탈퇴는 FK CASCADE. */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM CompanionRiskPatternNotice n WHERE n.wardId = :wardId")
    int deleteAllByWardId(@Param("wardId") String wardId);
}

package kr.silverbridge.main.domain.companion.repository;

import kr.silverbridge.main.domain.companion.entity.CompanionTurn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface CompanionTurnRepository extends JpaRepository<CompanionTurn, Long> {

    List<CompanionTurn> findBySessionIdOrderByTurnNoAsc(Long sessionId);

    boolean existsBySessionIdAndTurnNo(Long sessionId, int turnNo);

    /** 동의 철회 - 그 피보호자의 모든 대화 글을 비운다(기분은 남김). */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE CompanionTurn t SET t.botText = null, t.answerText = null
            WHERE (t.botText IS NOT NULL OR t.answerText IS NOT NULL)
              AND t.sessionId IN (SELECT s.id FROM CompanionSession s WHERE s.wardId = :wardId)
            """)
    int clearTextByWardId(@Param("wardId") String wardId);

    /** 30일 보관 - 기준 시각보다 오래된 턴의 글을 비운다(기분은 남김). */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE CompanionTurn t SET t.botText = null, t.answerText = null
            WHERE (t.botText IS NOT NULL OR t.answerText IS NOT NULL) AND t.createdAt < :cutoff
            """)
    int clearTextCreatedBefore(@Param("cutoff") OffsetDateTime cutoff);
}

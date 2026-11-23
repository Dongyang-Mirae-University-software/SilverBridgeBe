package kr.silverbridge.main.domain.companion.repository;

import kr.silverbridge.main.domain.companion.entity.CompanionMood;
import kr.silverbridge.main.domain.companion.entity.CompanionTurn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface CompanionTurnRepository extends JpaRepository<CompanionTurn, Long> {

    List<CompanionTurn> findBySessionIdOrderByTurnNoAsc(Long sessionId);

    boolean existsBySessionIdAndTurnNo(Long sessionId, int turnNo);

    /** 대화별 판정된 턴 기분(판단 보류 = null 은 뺀다) - 하루 기분을 그날 모든 턴으로 정할 때 쓴다(2026-11-23). */
    @Query("""
            SELECT t.sessionId AS sessionId, t.mood AS mood FROM CompanionTurn t
            WHERE t.sessionId IN :sessionIds AND t.mood IS NOT NULL
            """)
    List<TurnMood> findMoodsBySessionIdIn(@Param("sessionIds") Collection<Long> sessionIds);

    interface TurnMood {
        Long getSessionId();

        CompanionMood getMood();
    }

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

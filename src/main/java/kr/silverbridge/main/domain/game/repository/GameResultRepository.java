package kr.silverbridge.main.domain.game.repository;

import kr.silverbridge.main.domain.game.entity.GameResult;
import kr.silverbridge.main.global.enums.GameType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface GameResultRepository extends JpaRepository<GameResult, Long> {

    // 피보호자 + 게임 유형 + 날짜 범위 필터 조회 (관리자용)
    @Query("""
            SELECT gr FROM GameResult gr
            WHERE (:userId IS NULL OR gr.userId = :userId)
            AND (:gameType IS NULL OR gr.gameType = :gameType)
            AND (:startDate IS NULL OR gr.playedAt >= :startDate)
            AND (:endDate IS NULL OR gr.playedAt <= :endDate)
            ORDER BY gr.playedAt DESC
            """)
    Page<GameResult> findByFilters(
            @Param("userId") String userId,
            @Param("gameType") GameType gameType,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate,
            Pageable pageable
    );
}

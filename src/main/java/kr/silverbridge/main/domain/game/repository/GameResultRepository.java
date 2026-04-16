package kr.silverbridge.main.domain.game.repository;

import kr.silverbridge.main.domain.game.entity.GameResult;
import kr.silverbridge.main.global.enums.GameType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

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

    // 사용자 게임 결과 최신순 조회 (피보호자 전용)
    Page<GameResult> findByUserIdOrderByPlayedAtDesc(String userId, Pageable pageable);

    // 게임 유형별 최고 점수 랭킹 (피보호자 전체)
    @Query("""
            SELECT gr FROM GameResult gr
            WHERE gr.gameType = :gameType AND gr.isCleared = true
            AND gr.playedAt = (
                SELECT MAX(gr2.playedAt) FROM GameResult gr2
                WHERE gr2.userId = gr.userId AND gr2.gameType = :gameType AND gr2.isCleared = true
                AND gr2.score = (SELECT MAX(gr3.score) FROM GameResult gr3 WHERE gr3.userId = gr.userId AND gr3.gameType = :gameType AND gr3.isCleared = true)
            )
            ORDER BY gr.score DESC, gr.durationSeconds ASC
            """)
    Page<GameResult> findTopScoresByGameType(
            @Param("gameType") GameType gameType,
            Pageable pageable
    );

    // 성능 저하 감지용: 특정 사용자의 최근 N개 결과 조회 (플레이 시간순)
    @Query("""
            SELECT gr FROM GameResult gr
            WHERE gr.userId = :userId
            ORDER BY gr.playedAt DESC
            """)
    List<GameResult> findRecentByUserId(@Param("userId") String userId, Pageable pageable);

    // 사용자별 총 게임 횟수 + 클리어 횟수 + 평균 점수 (랭킹 집계용)
    @Query("""
            SELECT gr.userId,
                   COUNT(gr) as totalCount,
                   SUM(CASE WHEN gr.isCleared = true THEN 1 ELSE 0 END) as clearedCount,
                   AVG(CASE WHEN gr.score IS NOT NULL THEN gr.score ELSE 0 END) as avgScore
            FROM GameResult gr
            WHERE gr.gameType = :gameType
            GROUP BY gr.userId
            ORDER BY avgScore DESC
            """)
    Page<Object[]> findRankingByGameType(@Param("gameType") GameType gameType, Pageable pageable);
}

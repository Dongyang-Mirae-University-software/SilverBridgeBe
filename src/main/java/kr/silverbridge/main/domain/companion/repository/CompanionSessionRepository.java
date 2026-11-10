package kr.silverbridge.main.domain.companion.repository;

import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSessionStatus;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface CompanionSessionRepository extends JpaRepository<CompanionSession, Long> {

    List<CompanionSession> findByWardIdAndSessionDate(String wardId, LocalDate sessionDate);

    /** 일반 대화(시간대당 1행). 테스트 대화는 같은 시간대에 여러 행일 수 있어 빼고 찾는다(2026-11-10). */
    Optional<CompanionSession> findByWardIdAndSessionDateAndSlotAndTestFalse(String wardId, LocalDate sessionDate,
                                                                             CompanionSlot slot);

    /** 같은 시간대의 진행 중인 테스트 대화 중 가장 최근 것(테스트 시작 때 이어 가기용). */
    Optional<CompanionSession> findFirstByWardIdAndSessionDateAndSlotAndTestTrueAndStatusOrderByIdDesc(
            String wardId, LocalDate sessionDate, CompanionSlot slot, CompanionSessionStatus status);

    List<CompanionSession> findBySessionDate(LocalDate sessionDate);

    /** 연속 일수 계산용 - 한 피보호자의 {@code from} 이후 마친 대화. */
    @Query("SELECT s FROM CompanionSession s WHERE s.wardId = :wardId AND s.status = :status "
            + "AND s.sessionDate >= :from")
    List<CompanionSession> findFinishedSince(@Param("wardId") String wardId,
                                             @Param("status") CompanionSessionStatus status,
                                             @Param("from") LocalDate from);

    /** 관리자 집계용 - 모든 피보호자의 {@code from} 이후 마친 대화. */
    @Query("SELECT s FROM CompanionSession s WHERE s.status = :status AND s.sessionDate >= :from")
    List<CompanionSession> findAllFinishedSince(@Param("status") CompanionSessionStatus status,
                                                @Param("from") LocalDate from);

    /** 피보호자별 마지막으로 대답한 대화가 끝난 시각(관리자 "오늘 대화가 없는 어르신"). */
    @Query("SELECT s.wardId, MAX(s.endedAt) FROM CompanionSession s WHERE s.wardId IN :wardIds "
            + "AND s.status = :status AND s.answeredCount > 0 GROUP BY s.wardId")
    List<Object[]> findLastAnsweredAt(@Param("wardIds") java.util.Collection<String> wardIds,
                                      @Param("status") CompanionSessionStatus status);

    /** 관리자 집계 - 그 날 보낸 위험 알림 수(대화당 1번이라 대화 수와 같다). */
    @Query("SELECT COUNT(s) FROM CompanionSession s WHERE s.riskNotifiedAt >= :from AND s.riskNotifiedAt < :to")
    long countRiskNotifiedBetween(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /** 오래 멈춘 대화 - 마지막 활동이 {@code cutoff} 이전인 OPEN. 오래된 것부터. */
    @Query("SELECT s FROM CompanionSession s WHERE s.status = :status AND s.lastActivityAt < :cutoff "
            + "ORDER BY s.lastActivityAt ASC")
    List<CompanionSession> findStale(@Param("status") CompanionSessionStatus status,
                                     @Param("cutoff") OffsetDateTime cutoff, Pageable pageable);

    boolean existsByWardId(String wardId);

    /**
     * 역할 변경(피보호자 → 보호자) 때 그 사람의 말벗 기록을 지운다(2026-11-04 점검 M-1). 관리자 회원 수정 트랜잭션 안에서
     * 돌기 때문에 영속성 컨텍스트를 비우지 않는다(clear하면 그 트랜잭션의 다른 엔티티가 분리된다).
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM CompanionSession s WHERE s.wardId = :wardId")
    int deleteAllByWardId(@Param("wardId") String wardId);

    /**
     * 위험 알림 선점 - 아직 보내지 않았을 때만 시각을 남긴다. 1이면 이 호출이 보낼 차례다(대화당 1번, 결정 J).
     * 턴 요청·마감 요청·마감 스케줄러가 동시에 와도 한쪽만 1을 받는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE CompanionSession s SET s.riskNotifiedAt = :now WHERE s.id = :id AND s.riskNotifiedAt IS NULL")
    int claimRiskNotification(@Param("id") Long id, @Param("now") OffsetDateTime now);
}

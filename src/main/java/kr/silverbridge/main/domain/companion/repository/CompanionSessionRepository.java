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

    Optional<CompanionSession> findByWardIdAndSessionDateAndSlot(String wardId, LocalDate sessionDate,
                                                                 CompanionSlot slot);

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

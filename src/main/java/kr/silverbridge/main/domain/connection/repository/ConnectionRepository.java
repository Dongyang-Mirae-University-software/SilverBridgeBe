package kr.silverbridge.main.domain.connection.repository;

import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface ConnectionRepository extends JpaRepository<Connection, Long> {

    // 보호자 기준 전체 연결 목록 조회 (요청 이력, 최신 요청순)
    List<Connection> findByGuardianIdOrderByCreatedAtDesc(String guardianId);

    // 보호자 기준 복수 상태 연결 목록 조회 (ACTIVE + PENDING, 최신 요청순)
    List<Connection> findByGuardianIdAndStatusInOrderByCreatedAtDesc(
            String guardianId, List<ConnectionStatus> statuses);

    // 피보호자의 상태별 보호자 목록 (연결 생성 오래된 순 / ACTIVE 보호자 목록)
    List<Connection> findByWardIdAndStatusOrderByCreatedAtAsc(String wardId, ConnectionStatus status);

    // 피보호자의 상태별 보호자 목록 (요청일 최신순 / PENDING "요청온 목록")
    List<Connection> findByWardIdAndStatusOrderByCreatedAtDesc(String wardId, ConnectionStatus status);

    // 동일한 guardian-ward 쌍의 live(PENDING/ACTIVE) 연결 존재 여부 — 중복 요청·연결 차단용
    boolean existsByGuardianIdAndWardIdAndStatusIn(String guardianId, String wardId, List<ConnectionStatus> statuses);

    // 사용자가 보호자 또는 피보호자로 참여 중인 특정 상태 연결 — 회원 탈퇴 시 일괄 정리용 (D-USER-3)
    @Query("SELECT c FROM Connection c WHERE (c.guardianId = :userId OR c.wardId = :userId) AND c.status IN :statuses")
    List<Connection> findByParticipantAndStatusIn(@Param("userId") String userId,
                                                  @Param("statuses") List<ConnectionStatus> statuses);

    // ===== 관리자 대시보드 집계 =====

    /** 상태별 연결 수(대기 중인 연결 요청 등). */
    long countByStatus(ConnectionStatus status);

    /**
     * 오래 방치된 연결 요청 수. 기준 시각은 호출부가 계산해 넘긴다.
     *
     * <p>수락도 거절도 없이 오래 남은 요청은 보호자가 잊었거나 피보호자가 알림을 못 본 경우라,
     * 관리자가 확인해야 할 사각지대 신호다.</p>
     */
    long countByStatusAndCreatedAtBefore(ConnectionStatus status, OffsetDateTime cutoff);
}

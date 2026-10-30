package kr.silverbridge.main.global.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {

    // 특정 관리자의 행동 이력 조회 (최신순)
    List<AdminAuditLog> findByAdminIdOrderByCreatedAtDesc(String adminId);

    /** 보관 기간이 지난 감사 로그 삭제(2026-10-31 전체 점검 D-2). 반환: 삭제 건수. */
    @Transactional
    @Modifying
    @Query("DELETE FROM AdminAuditLog a WHERE a.createdAt < :threshold")
    int deleteOlderThan(@Param("threshold") OffsetDateTime threshold);
}

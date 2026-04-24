package kr.silverbridge.main.domain.admin.repository;

import kr.silverbridge.main.domain.admin.entity.AdminAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {

    // 특정 관리자의 행동 이력 조회 (최신순)
    List<AdminAuditLog> findByAdminIdOrderByCreatedAtDesc(String adminId);
}

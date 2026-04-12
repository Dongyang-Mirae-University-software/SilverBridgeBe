package kr.silverbridge.main.domain.admin.repository;

import kr.silverbridge.main.domain.admin.entity.AdminAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {

    // 특정 관리자의 행동 이력 조회
    Page<AdminAuditLog> findByAdminId(String adminId, Pageable pageable);
}

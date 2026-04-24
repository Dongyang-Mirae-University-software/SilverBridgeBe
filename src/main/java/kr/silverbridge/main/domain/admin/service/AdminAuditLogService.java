package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.dto.AdminAuditLogResponse;
import kr.silverbridge.main.domain.admin.entity.AdminAuditLog;
import kr.silverbridge.main.domain.admin.repository.AdminAuditLogRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.AdminAuditAction;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminAuditLogService {

    private final AdminAuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    // 관리자 행동 기록
    @Transactional
    public void log(String adminId, AdminAuditAction action, String targetId, String detail) {
        auditLogRepository.save(AdminAuditLog.builder()
                .adminId(adminId)
                .action(action)
                .targetId(targetId)
                .detail(detail)
                .build());
    }

    // 전체 감사 로그 조회 (최신순)
    @Transactional(readOnly = true)
    public List<AdminAuditLogResponse> getLogs() {
        return auditLogRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
                .map(log -> {
                    User admin = userRepository.findById(log.getAdminId()).orElse(null);
                    return AdminAuditLogResponse.of(log, admin);
                })
                .toList();
    }
}

package kr.silverbridge.main.domain.admin.entity;

import jakarta.persistence.*;
import kr.silverbridge.main.global.enums.AdminAuditAction;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;

@Entity
@Table(name = "admin_audit_log", indexes = {
        @Index(name = "idx_admin_audit_logs_admin_created", columnList = "admin_id, created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class AdminAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 행동한 관리자 ID (탈퇴 시에도 이력 보존을 위해 FK 없이 저장)
    @Column(name = "admin_id", nullable = false, length = 6)
    private String adminId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AdminAuditAction action;

    // 대상 ID (userId, connectionId, announcementId 등)
    @Column(name = "target_id", length = 100)
    private String targetId;

    // 변경 내용 요약 (예: "상태 변경: ACTIVE → INACTIVE")
    @Column(length = 500)
    private String detail;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}

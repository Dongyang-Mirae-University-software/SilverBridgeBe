package kr.silverbridge.main.domain.connection.entity;

import jakarta.persistence.*;
import kr.silverbridge.main.global.entity.BaseTimeEntity;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "connections", indexes = {
        @Index(name = "idx_connections_guardian_status", columnList = "guardian_id, status")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class Connection extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "guardian_id", nullable = false, length = 6)
    private String guardianId;

    @Column(name = "ward_id", nullable = false, length = 6)
    private String wardId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConnectionStatus status;

    @Column(name = "initiated_by", length = 6)
    private String initiatedBy;

    @Column(name = "connected_at")
    private OffsetDateTime connectedAt;

    // 연결 활성화 (ACTIVE 전환)
    public void activate() {
        this.status = ConnectionStatus.ACTIVE;
        this.connectedAt = OffsetDateTime.now();
    }

    // 연결 해제 (CANCELLED 전환)
    public void cancel() {
        this.status = ConnectionStatus.CANCELLED;
    }
}

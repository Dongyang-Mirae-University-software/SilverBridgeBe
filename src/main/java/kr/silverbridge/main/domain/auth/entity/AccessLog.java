package kr.silverbridge.main.domain.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "access_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class AccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "client_id", length = 36)
    private String clientId;

    // LOGIN, LOGOUT, KAKAO_LOGIN, TOKEN_ISSUE, PASSWORD_RESET
    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public AccessLog(String userId, String clientId, String action,
                     String ipAddress, String userAgent) {
        this.userId = userId;
        this.clientId = clientId;
        this.action = action;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }
}

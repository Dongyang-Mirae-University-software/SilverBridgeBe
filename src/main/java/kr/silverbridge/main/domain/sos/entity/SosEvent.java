package kr.silverbridge.main.domain.sos.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;

/**
 * 피보호자 SOS 발생 이력. 피보호자가 긴급 SOS를 누를 때마다 한 행이 기록된다.
 *
 * <p>이력은 알림 발송과 무관하게 항상 남는다(SOS 처리의 진실원본). 발송된 보호자 목록은 저장하지 않으며,
 * 알림 대상은 발송 시점의 ACTIVE connections에서 도출된다. {@code wardId}는 탈퇴(hard delete) 시
 * {@code ON DELETE SET NULL}로 익명 보존된다(access_logs와 동일 정책).</p>
 */
@Entity
@Table(name = "sos_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class SosEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ward_id", length = 6)
    private String wardId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    public SosEvent(String wardId) {
        this.wardId = wardId;
    }
}

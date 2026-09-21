package kr.silverbridge.main.domain.anomaly.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 동수(CONFLICTED) 재확인 안내 기록 겸 중복 방지.
 *
 * <p>상황당 보호자당 한 번뿐이다({@code uq_anomaly_review_conflict}) - 번복으로 동수와 다수를 오가도
 * 다시 보내지 않는다. 안내는 선점 후 발송(행 먼저 커밋 → 발송)이라 재촉 로그와 같은 이유로 중복이 없다.</p>
 *
 * <p>{@code sent=false}는 "보내지 않고 처리 완료"다. 방금 답해 동수를 만든 보호자는 응답 API가 이미
 * CONFLICTED를 돌려줬으므로 안내하지 않고, 이 행만 남겨 스케줄러가 건너뛰게 한다.</p>
 */
@Entity
@Table(name = "anomaly_review_conflict_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnomalyReviewConflictLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    @Column(name = "guardian_id", nullable = false, length = 6)
    private String guardianId;

    @Column(name = "sent", nullable = false)
    private boolean sent;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Builder
    private AnomalyReviewConflictLog(Long incidentId, String guardianId, boolean sent, OffsetDateTime createdAt) {
        this.incidentId = incidentId;
        this.guardianId = guardianId;
        this.sent = sent;
        this.createdAt = createdAt;
    }
}

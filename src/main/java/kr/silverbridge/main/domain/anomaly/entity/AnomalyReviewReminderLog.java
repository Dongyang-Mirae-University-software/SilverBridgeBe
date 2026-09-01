package kr.silverbridge.main.domain.anomaly.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 건별 재촉 발송 기록 겸 중복 방지.
 *
 * <p><b>발송 "전"에 이 행을 먼저 커밋하고 보낸다</b>(선점 후 발송). 순서를 뒤집으면 발송 직후 앱이
 * 죽었을 때 다음 주기에 또 보내고, 스케줄러가 5분마다 돌기 때문에 마감(3일) 내내 같은 재촉이 반복된다.
 * 대가로 발송 실패 시 그 건은 유실되지만, 재촉이 두 번 가는 쪽이 한 번 빠지는 쪽보다 나쁘고
 * 하루 1회 요약이 두 번째 기회다.</p>
 *
 * <p>상황당 보호자당 1건이라 회차 컬럼이 없다({@code uq_anomaly_review_reminder}).</p>
 */
@Entity
@Table(name = "anomaly_review_reminder_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnomalyReviewReminderLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    @Column(name = "guardian_id", nullable = false, length = 6)
    private String guardianId;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;

    @Builder
    private AnomalyReviewReminderLog(Long incidentId, String guardianId, OffsetDateTime sentAt) {
        this.incidentId = incidentId;
        this.guardianId = guardianId;
        this.sentAt = sentAt;
    }
}

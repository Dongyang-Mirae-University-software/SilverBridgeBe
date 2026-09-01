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

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 하루 1회 미응답 요약의 발송 기록 겸 중복 방지.
 *
 * <p>축은 <b>(보호자, 날짜)</b>다 - 요약은 그 보호자가 하루 한 번 받는다. 상황 단위로 쪼개 건별
 * 발송으로 바꾸지 말 것: 알림 피로로 보호자가 앱 알림을 통째로 끄면 SOS·이상감지 같은
 * 필수 알림까지 함께 죽는다.</p>
 *
 * <p>건별 재촉({@link AnomalyReviewReminderLog})과 축이 달라 테이블을 나눴다.</p>
 */
@Entity
@Table(name = "anomaly_review_summary_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnomalyReviewSummaryLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "guardian_id", nullable = false, length = 6)
    private String guardianId;

    /** KST 기준 날짜. 서버·DB 타임존을 따르면 자정 전후 요약이 전날로 기록된다. */
    @Column(name = "summary_date", nullable = false)
    private LocalDate summaryDate;

    @Column(name = "pending_count", nullable = false)
    private int pendingCount;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;

    @Builder
    private AnomalyReviewSummaryLog(String guardianId, LocalDate summaryDate, int pendingCount, OffsetDateTime sentAt) {
        this.guardianId = guardianId;
        this.summaryDate = summaryDate;
        this.pendingCount = pendingCount;
        this.sentAt = sentAt;
    }
}

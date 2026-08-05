package kr.silverbridge.main.domain.medication.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 미복용 요약 알림 발송 기록. <b>행의 존재 = 그 보호자에게 그 피보호자 건을 그날 이미 보냈다</b>는 뜻이다.
 *
 * <p>{@code (guardian_id, ward_id, dose_date)} UNIQUE라 스케줄러가 1분마다 돌아도 하루 한 번만 나간다.
 * 축이 <b>약 단위가 아니라 (보호자, 피보호자, 날짜)</b>인 것이 2차의 {@link MedicationReminderLog}와 다른 점이다 —
 * 요약 알림이라 약 3건이 미체크여도 알림은 1건이다.</p>
 *
 * <p>2차와 동일하게 <b>선점 후 발송</b>이다 — 이 행을 먼저 커밋하고 보낸다.</p>
 */
@Entity
@Table(name = "medication_missed_alert_log", uniqueConstraints = {
        @UniqueConstraint(name = "uq_medication_missed_alert",
                columnNames = {"guardian_id", "ward_id", "dose_date"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MedicationMissedAlertLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "guardian_id", nullable = false, length = 6)
    private String guardianId;

    @Column(name = "ward_id", nullable = false, length = 6)
    private String wardId;

    /** 요약 대상 날짜(KST 기준). */
    @Column(name = "dose_date", nullable = false)
    private LocalDate doseDate;

    /** 체크되지 않은 약 수. */
    @Column(name = "missed_count", nullable = false)
    private int missedCount;

    /** 판정 시각까지 복용 시각이 지난 약 수(=요약의 분모). */
    @Column(name = "total_count", nullable = false)
    private int totalCount;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;

    private MedicationMissedAlertLog(String guardianId, String wardId, LocalDate doseDate,
                                     int missedCount, int totalCount, OffsetDateTime sentAt) {
        this.guardianId = guardianId;
        this.wardId = wardId;
        this.doseDate = doseDate;
        this.missedCount = missedCount;
        this.totalCount = totalCount;
        this.sentAt = sentAt;
    }

    public static MedicationMissedAlertLog of(String guardianId, String wardId, LocalDate doseDate,
                                              int missedCount, int totalCount, OffsetDateTime sentAt) {
        return new MedicationMissedAlertLog(guardianId, wardId, doseDate, missedCount, totalCount, sentAt);
    }
}

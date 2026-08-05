package kr.silverbridge.main.domain.medication.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 복약 알림 발송 기록. <b>행의 존재 = 그 회차를 이미 보냈다</b>는 뜻이며, 중복 발송을 막는 근거다.
 *
 * <p>스케줄러는 1분마다 돌기 때문에 이 기록이 없으면 유예 창(기본 30분) 내내 같은 알림이 반복된다.
 * {@code (medication_id, dose_date, attempt)} UNIQUE가 최종 방어선이라, 주기가 겹쳐 돌거나 앱이
 * 재기동돼도 같은 회차는 한 번만 나간다.</p>
 *
 * <p><b>선점 후 발송</b>: 실제 발송 전에 이 행을 먼저 커밋한다. 발송이 실패해도 재시도하지 않는다 —
 * 알림이 두 번 가는 쪽이 한 번 빠지는 쪽보다 나쁘고, 재알림({@link #ATTEMPT_RETRY})이 두 번째 기회가 된다.</p>
 */
@Entity
@Table(name = "medication_reminder_log", uniqueConstraints = {
        @UniqueConstraint(name = "uq_medication_reminder",
                columnNames = {"medication_id", "dose_date", "attempt"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MedicationReminderLog {

    /** 복용 시각에 보내는 최초 알림. */
    public static final int ATTEMPT_FIRST = 1;
    /** 체크되지 않아 한 번 더 보내는 재알림. */
    public static final int ATTEMPT_RETRY = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "medication_id", nullable = false)
    private Long medicationId;

    /** 복용 예정일(KST 기준). */
    @Column(name = "dose_date", nullable = false)
    private LocalDate doseDate;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;

    private MedicationReminderLog(Long medicationId, LocalDate doseDate, int attempt, OffsetDateTime sentAt) {
        this.medicationId = medicationId;
        this.doseDate = doseDate;
        this.attempt = attempt;
        this.sentAt = sentAt;
    }

    public static MedicationReminderLog of(Long medicationId, LocalDate doseDate, int attempt, OffsetDateTime sentAt) {
        return new MedicationReminderLog(medicationId, doseDate, attempt, sentAt);
    }
}

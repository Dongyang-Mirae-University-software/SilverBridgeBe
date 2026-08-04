package kr.silverbridge.main.domain.medication.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 특정 날짜의 복용 체크 한 건. <b>행의 존재 자체가 "그날 복용했다"</b>는 뜻이고, 체크 해제는 행 삭제다.
 *
 * <p>{@code (medication_id, dose_date)} UNIQUE라 같은 약을 같은 날 두 번 체크할 수 없다 — 네트워크 재시도나
 * 더블 탭으로 중복 요청이 와도 결과가 같다(멱등).</p>
 *
 * <p>{@code doseDate}는 <b>KST 기준 날짜</b>다. 서버·DB 타임존과 무관하게 "어르신에게 오늘"이 기준이어야
 * 자정 전후로 체크가 엉키지 않는다.</p>
 *
 * <p>약이 삭제되어도(soft delete) 이 행은 남는다 — 지난 복용 이력을 보존하기 위함이다. 실제로 사라지는 건
 * 피보호자·등록 보호자 탈퇴로 {@code medication} 행이 hard delete될 때뿐이다(FK CASCADE).</p>
 */
@Entity
@Table(name = "medication_intake", uniqueConstraints = {
        @UniqueConstraint(name = "uq_medication_intake", columnNames = {"medication_id", "dose_date"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MedicationIntake {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "medication_id", nullable = false)
    private Long medicationId;

    /** 복용 예정일(KST 기준). */
    @Column(name = "dose_date", nullable = false)
    private LocalDate doseDate;

    /** 피보호자가 실제로 체크한 시각. */
    @Column(name = "taken_at", nullable = false)
    private OffsetDateTime takenAt;

    private MedicationIntake(Long medicationId, LocalDate doseDate, OffsetDateTime takenAt) {
        this.medicationId = medicationId;
        this.doseDate = doseDate;
        this.takenAt = takenAt;
    }

    public static MedicationIntake of(Long medicationId, LocalDate doseDate, OffsetDateTime takenAt) {
        return new MedicationIntake(medicationId, doseDate, takenAt);
    }
}

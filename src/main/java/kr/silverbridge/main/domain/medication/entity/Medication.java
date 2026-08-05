package kr.silverbridge.main.domain.medication.entity;

import jakarta.persistence.*;
import kr.silverbridge.main.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * 피보호자의 약 한 종류. 매일 같은 시각에 반복되는 일정이며, 특정 날짜의 복용 여부는
 * {@link MedicationIntake}가 별도로 담는다.
 *
 * <p><b>등록 주체는 보호자</b>다 — 피보호자가 약 이름·용량을 직접 입력하는 부담을 덜기 위한 결정이라
 * 피보호자에게는 등록 API 자체가 없다. 반대로 <b>복용 체크는 피보호자만</b> 할 수 있다(보호자 화면의
 * 체크 표시는 읽기 전용). 이 비대칭이 "피보호자가 체크해야 보호자에게 보인다"는 요구를 구조로 보장한다.</p>
 *
 * <p><b>soft delete</b>: 삭제는 {@code deletedAt}을 채우고 행은 남긴다 — 지난 복용 이력
 * ({@code medication_intake})이 FK CASCADE로 함께 사라지면 "그동안 잘 드셨는지"의 근거가 없어진다.
 * 조회는 모두 {@code deletedAt IS NULL}로 거른다.</p>
 *
 * <p>{@code createdBy}(등록 보호자)는 탈퇴 시 이 약도 함께 삭제된다(V35 CASCADE + 탈퇴 리스너).
 * 남은 보호자에게는 "중지되었다"는 안내가 나간다.</p>
 */
@Entity
@Table(name = "medication")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Medication extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 약을 복용하는 피보호자. 탈퇴 시 CASCADE로 함께 삭제된다. */
    @Column(name = "ward_id", nullable = false, length = 6)
    private String wardId;

    /** 약을 등록한 보호자. 탈퇴 시 이 약도 함께 삭제된다. */
    @Column(name = "created_by", nullable = false, length = 6)
    private String createdBy;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_slot", nullable = false, length = 20)
    private MedicationTimeSlot timeSlot;

    @Column(name = "dose_time", nullable = false)
    private LocalTime doseTime;

    /** 복용량(정). */
    @Column(name = "dose_amount", nullable = false)
    private int doseAmount;

    /** "식사와 함께" 같은 복용 안내(선택). */
    @Column(length = 100)
    private String memo;

    /** 삭제 시각. {@code null}이면 사용 중인 약이다. */
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Builder
    private Medication(String wardId, String createdBy, String name, MedicationTimeSlot timeSlot,
                       LocalTime doseTime, int doseAmount, String memo) {
        this.wardId = wardId;
        this.createdBy = createdBy;
        this.name = name;
        this.timeSlot = timeSlot;
        this.doseTime = doseTime;
        this.doseAmount = doseAmount;
        this.memo = memo;
    }

    /**
     * 약 정보를 갱신한다. <b>확정된 최종값</b>만 받는다 — "무엇을 바꾸지 않을지"(부분 수정의 null 병합)는
     * 서비스가 판단해 넘기고, 엔티티는 결과 상태만 반영한다.
     *
     * <p>{@code wardId}·{@code createdBy}는 바뀌지 않는다 — 소유자와 등록자는 약의 정체성이라
     * 옮기려면 삭제 후 재등록해야 한다.</p>
     *
     * @param memo 없으면 {@code null}(메모 삭제)
     */
    public void update(String name, MedicationTimeSlot timeSlot, LocalTime doseTime,
                       int doseAmount, String memo) {
        this.name = name;
        this.timeSlot = timeSlot;
        this.doseTime = doseTime;
        this.doseAmount = doseAmount;
        this.memo = memo;
    }

    /**
     * 약을 삭제 처리한다(soft delete). 이미 삭제된 약은 시각을 덮어쓰지 않는다 —
     * 최초 삭제 시점이 이력 해석의 기준이기 때문이다.
     */
    public void delete(OffsetDateTime deletedAt) {
        if (this.deletedAt == null) {
            this.deletedAt = deletedAt;
        }
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}

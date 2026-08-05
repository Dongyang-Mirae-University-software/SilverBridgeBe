package kr.silverbridge.main.domain.medication.entity;

import jakarta.persistence.*;
import kr.silverbridge.main.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보호자별 복약 알림 수신 설정. 보호자당 한 행({@code guardian_id} UNIQUE).
 *
 * <p>피보호자 단위인 {@link MedicationSetting}(복용 알림·재알림)과 <b>축이 다르다</b> —
 * 이쪽은 "보호자 본인이 무엇을 받을지"이고, 저쪽은 "그 피보호자에게 무엇을 보낼지"다.</p>
 *
 * <p>행이 없으면 기본값 ON을 따른다. 기본을 OFF로 두면 아무도 켜지 않아 기능이 죽고, 반대로 끌 수단이
 * 없으면 알림 피로로 <b>앱 알림을 통째로 꺼버려 SOS·이상감지까지 함께 죽는다</b> — 그래서 켜두되
 * 이 알림만 따로 끌 수 있게 한다.</p>
 */
@Entity
@Table(name = "guardian_medication_setting", uniqueConstraints = {
        @UniqueConstraint(name = "uq_guardian_medication_setting", columnNames = {"guardian_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GuardianMedicationSetting extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "guardian_id", nullable = false, length = 6)
    private String guardianId;

    /** 피보호자가 복약을 체크하지 않은 날 저녁 요약 알림을 받을지. */
    @Column(name = "missed_alert_enabled", nullable = false)
    private boolean missedAlertEnabled;

    private GuardianMedicationSetting(String guardianId, boolean missedAlertEnabled) {
        this.guardianId = guardianId;
        this.missedAlertEnabled = missedAlertEnabled;
    }

    public static GuardianMedicationSetting of(String guardianId, boolean missedAlertEnabled) {
        return new GuardianMedicationSetting(guardianId, missedAlertEnabled);
    }

    public void updateMissedAlertEnabled(boolean missedAlertEnabled) {
        this.missedAlertEnabled = missedAlertEnabled;
    }
}

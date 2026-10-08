package kr.silverbridge.main.domain.notification.entity;

import jakarta.persistence.*;
import kr.silverbridge.main.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보호자 계정 단위 알림 <b>종류</b> 수신 설정(채널 설정 {@link UserNotificationSetting}과 별개 축).
 *
 * <p>끌 수 있는 종류만 담는다. SOS·이상감지 발생 알림은 필수라 값이 없고, 정서 변화·병원 예약은 BE가
 * 보내지 않으므로 만들지 않는다. 행이 없으면 기본값 ON이다.</p>
 */
@Entity
@Table(name = "guardian_notification_preference", uniqueConstraints = {
        @UniqueConstraint(name = "uq_guardian_notification_preference", columnNames = {"guardian_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GuardianNotificationPreference extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "guardian_id", nullable = false, length = 6)
    private String guardianId;

    /** 복약 미복용 요약(MEDICATION_MISSED) 수신 여부. 피보호자별 설정과 AND로 합쳐진다. */
    @Column(name = "medication_enabled", nullable = false)
    private boolean medicationEnabled;

    public static GuardianNotificationPreference of(String guardianId, boolean medicationEnabled) {
        GuardianNotificationPreference preference = new GuardianNotificationPreference();
        preference.guardianId = guardianId;
        preference.medicationEnabled = medicationEnabled;
        return preference;
    }

    public void changeMedicationEnabled(boolean enabled) {
        this.medicationEnabled = enabled;
    }
}

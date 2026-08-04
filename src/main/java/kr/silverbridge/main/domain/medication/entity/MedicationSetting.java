package kr.silverbridge.main.domain.medication.entity;

import jakarta.persistence.*;
import kr.silverbridge.main.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 피보호자별 복약 알림 ON/OFF. 사용자당 한 행({@code user_id} UNIQUE).
 *
 * <p>행이 없는 사용자는 "미설정"으로 보고 기본값(ON)을 따른다 — 기존 사용자 백필 마이그레이션이 필요 없다
 * ({@code SosSetting}·{@code UserNotificationSetting}과 동일한 방식).</p>
 *
 * <p>토글은 <b>보호자 화면</b>에 있지만 값은 <b>피보호자 계정</b>에 붙는다 — 한 피보호자에게 보호자가 여럿
 * 붙을 수 있고, 알림을 받는 주체는 피보호자이기 때문이다. 보호자 A가 끄면 B의 화면에도 꺼진 것으로 보인다.</p>
 *
 * <p>현재는 값을 보관·조회만 한다. 복용 시각 알림 발송(스케줄러)은 2차 과제이며, 그때 이 값이 발송 여부를
 * 가르는 게이트가 된다.</p>
 */
@Entity
@Table(name = "medication_setting", uniqueConstraints = {
        @UniqueConstraint(name = "uq_medication_setting_user", columnNames = {"user_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MedicationSetting extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 6)
    private String userId;

    @Column(name = "alarm_enabled", nullable = false)
    private boolean alarmEnabled;

    private MedicationSetting(String userId, boolean alarmEnabled) {
        this.userId = userId;
        this.alarmEnabled = alarmEnabled;
    }

    public static MedicationSetting of(String userId, boolean alarmEnabled) {
        return new MedicationSetting(userId, alarmEnabled);
    }

    public void updateAlarmEnabled(boolean alarmEnabled) {
        this.alarmEnabled = alarmEnabled;
    }
}

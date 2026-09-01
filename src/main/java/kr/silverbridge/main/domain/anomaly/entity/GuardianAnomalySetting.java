package kr.silverbridge.main.domain.anomaly.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.silverbridge.main.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보호자별 이상감지 재촉 수신 설정.
 *
 * <p>보호자가 <b>이 알림만</b> 끌 수 있어야 한다. 그러지 못하면 알림 피로로 앱 알림을 통째로 꺼버리고,
 * 그때 SOS·이상감지 같은 필수 알림까지 함께 죽는다({@code guardian_medication_setting}과 같은 이유).</p>
 *
 * <p>행이 없으면 기본값 ON이다 - 기존 사용자 백필이 필요 없고, "정하지 않았다"와 "켜 두기로 했다"를
 * 굳이 구분할 필요가 없기 때문이다.</p>
 *
 * <p>축이 <b>보호자 단위</b>라는 점이 복약 설정(보호자 × 피보호자)과 다르다. 재촉은 판정 대상인
 * "상황"에 붙지 특정 피보호자에 붙지 않아, 피보호자별로 나눌 근거가 없다.</p>
 */
@Entity
@Table(name = "guardian_anomaly_setting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GuardianAnomalySetting extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "guardian_id", nullable = false, length = 6)
    private String guardianId;

    @Column(name = "review_reminder_enabled", nullable = false)
    private boolean reviewReminderEnabled;

    @Builder
    private GuardianAnomalySetting(String guardianId, boolean reviewReminderEnabled) {
        this.guardianId = guardianId;
        this.reviewReminderEnabled = reviewReminderEnabled;
    }

    public void changeReviewReminderEnabled(boolean enabled) {
        this.reviewReminderEnabled = enabled;
    }
}

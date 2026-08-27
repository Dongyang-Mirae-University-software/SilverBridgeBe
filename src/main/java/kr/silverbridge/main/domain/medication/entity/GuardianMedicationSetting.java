package kr.silverbridge.main.domain.medication.entity;

import jakarta.persistence.*;
import kr.silverbridge.main.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * 보호자가 <b>특정 피보호자에 대해</b> 받을 미복용 요약 설정.
 * (보호자, 피보호자)당 한 행({@code (guardian_id, ward_id)} UNIQUE).
 *
 * <p>피보호자 단위인 {@link MedicationSetting}(복용 알림·재알림)과 <b>축이 다르다</b> -
 * 저쪽은 "그 피보호자에게 무엇을 보낼지"라 보호자 여럿이 공유하고, 이쪽은 "내가 무엇을 언제 받을지"라
 * 보호자마다 따로 가진다. 같은 피보호자를 보는 보호자 둘이 서로 다른 시각을 지정할 수 있다.</p>
 *
 * <p><b>축이 (보호자) → (보호자, 피보호자)로 바뀐 이유</b>(2026-08-27, V41): 발송 시각이 집계 상한을
 * 겸하는데 피보호자마다 마지막 복약 시각이 달라, 값이 하나면 늦게 드시는 분의 약이 매일 요약에서 빠지거나
 * 반대로 일찍 끝나는 분의 요약까지 밤늦게 도착한다.</p>
 *
 * <p>행이 없으면 기본값 ON을 따른다. 기본을 OFF로 두면 아무도 켜지 않아 기능이 죽고, 반대로 끌 수단이
 * 없으면 알림 피로로 <b>앱 알림을 통째로 꺼버려 SOS·이상감지까지 함께 죽는다</b> - 그래서 켜두되
 * 이 알림만 따로 끌 수 있게 한다.</p>
 */
@Entity
@Table(name = "guardian_medication_setting", uniqueConstraints = {
        @UniqueConstraint(name = "uq_guardian_medication_setting", columnNames = {"guardian_id", "ward_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GuardianMedicationSetting extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "guardian_id", nullable = false, length = 6)
    private String guardianId;

    /** 어느 피보호자 건 요약인지. */
    @Column(name = "ward_id", nullable = false, length = 6)
    private String wardId;

    /** 이 피보호자가 복약을 체크하지 않은 날 요약 알림을 받을지. */
    @Column(name = "missed_alert_enabled", nullable = false)
    private boolean missedAlertEnabled;

    /**
     * 요약을 받을 시각(KST). {@code null}이면 전역 기본값(21:00)을 따른다.
     *
     * <p><b>발송 시각이자 집계 상한이다</b> - 이 시각까지 복용 시각이 지난 약만 센다.
     * 아직 먹을 때가 아닌 약을 미복용으로 통보하면 매일 거짓 알림이 되기 때문이다.
     * 이르게 잡을수록 그날 요약에 포함되는 약이 줄어드는 것은 의도된 동작이다.</p>
     */
    @Column(name = "missed_alert_time")
    private LocalTime missedAlertTime;

    private GuardianMedicationSetting(String guardianId, String wardId,
                                      boolean missedAlertEnabled, LocalTime missedAlertTime) {
        this.guardianId = guardianId;
        this.wardId = wardId;
        this.missedAlertEnabled = missedAlertEnabled;
        this.missedAlertTime = missedAlertTime;
    }

    /** 시각 미지정(전역 기본값을 따르는) 상태로 만든다. */
    public static GuardianMedicationSetting of(String guardianId, String wardId, boolean missedAlertEnabled) {
        return new GuardianMedicationSetting(guardianId, wardId, missedAlertEnabled, null);
    }

    public void updateMissedAlertEnabled(boolean missedAlertEnabled) {
        this.missedAlertEnabled = missedAlertEnabled;
    }

    /**
     * 발송 시각을 바꾼다. 스케줄러가 분 단위로 돌므로 초·나노는 버린다 -
     * 저장해 봐야 판정에 쓰이지 않으면서 조회 결과만 지저분해진다.
     */
    public void updateMissedAlertTime(LocalTime missedAlertTime) {
        this.missedAlertTime = missedAlertTime.truncatedTo(ChronoUnit.MINUTES);
    }
}

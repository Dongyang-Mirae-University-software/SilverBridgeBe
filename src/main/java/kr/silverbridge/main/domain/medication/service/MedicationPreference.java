package kr.silverbridge.main.domain.medication.service;

/**
 * 피보호자의 복약 알림 설정 값. 저장된 행이 없으면 {@link #DEFAULT}가 적용된다.
 *
 * <p>복용 시각 알림 자체는 <b>늘 보낸다</b> - 보호자 화면의 "약 드실 시간 알려 드리기" 스위치와 설정 API를
 * 2026-10-24 사용자 결정으로 없앴다(DB {@code medication_setting.alarm_enabled} 컬럼은 남아 있지만 읽지 않는다).</p>
 *
 * @param remindAgainEnabled 체크하지 않았을 때 한 번 더 보낼지
 */
public record MedicationPreference(boolean remindAgainEnabled) {

    /**
     * 설정한 적 없는 사용자에게 적용되는 값 - 재알림 켜짐.
     *
     * <p>어르신은 한 번으로 놓치는 경우가 많아 재알림을 기본 켜짐으로 둔다. 덕분에 기존 사용자 백필도 필요 없다.</p>
     */
    public static final MedicationPreference DEFAULT = new MedicationPreference(true);
}

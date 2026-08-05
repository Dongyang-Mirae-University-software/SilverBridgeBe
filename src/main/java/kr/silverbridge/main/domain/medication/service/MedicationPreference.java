package kr.silverbridge.main.domain.medication.service;

/**
 * 피보호자의 복약 알림 설정 값. 저장된 행이 없으면 {@link #DEFAULT}가 적용된다.
 *
 * @param alarmEnabled       복약 알림 전체 ON/OFF. false면 최초 알림도 재알림도 보내지 않는다.
 * @param remindAgainEnabled 체크하지 않았을 때 한 번 더 보낼지
 */
public record MedicationPreference(boolean alarmEnabled, boolean remindAgainEnabled) {

    /**
     * 설정한 적 없는 사용자에게 적용되는 값 — 둘 다 켜짐.
     *
     * <p>약을 등록했는데 알림이 꺼진 게 기본이면 기능이 무의미하고, 어르신은 한 번으로 놓치는 경우가
     * 많아 재알림도 기본 켜짐으로 둔다. 덕분에 기존 사용자 백필도 필요 없다.</p>
     */
    public static final MedicationPreference DEFAULT = new MedicationPreference(true, true);
}

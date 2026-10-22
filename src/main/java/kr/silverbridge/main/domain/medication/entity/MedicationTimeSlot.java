package kr.silverbridge.main.domain.medication.entity;

import java.time.LocalTime;

/**
 * 복용 시간대. 약 추가 화면의 "복용 시간" 선택지에 대응한다.
 *
 * <p>슬롯은 <b>표시용 구분</b>이고, 실제 시각은 {@code Medication.doseTime}에 따로 저장한다 — 같은 "아침"이라도
 * 07:00에 드시는 분과 08:00에 드시는 분이 있어서다. 요청이 시각을 생략하면 {@link #defaultTime()}을 쓴다.</p>
 *
 * <p>정렬·표시 문구(예: "아침 08:00", "취침 전 22:00")는 프론트가 조립한다 — 서버는 슬롯과 시각만 준다.</p>
 */
public enum MedicationTimeSlot {

    MORNING(LocalTime.of(8, 0), "아침"),
    LUNCH(LocalTime.of(13, 0), "점심"),
    DINNER(LocalTime.of(18, 0), "저녁"),
    BEDTIME(LocalTime.of(22, 0), "취침 전");

    private final LocalTime defaultTime;
    private final String label;

    MedicationTimeSlot(LocalTime defaultTime, String label) {
        this.defaultTime = defaultTime;
        this.label = label;
    }

    /**
     * 복용 시각으로 시간대를 정한다(일괄 등록용, 2026-10-22). 04:00~10:59 아침 / 11:00~15:59 점심 / 16:00~20:59 저녁 /
     * 21:00~03:59 취침 전. 각 시간대의 기본 시각(08·13·18·22시)은 자기 시간대로 돌아온다.
     * 새벽(00~03시) 약을 "아침"으로 넣으면 아침 약 알림처럼 읽혀 "취침 전"에 둔다.
     */
    public static MedicationTimeSlot fromDoseTime(LocalTime doseTime) {
        int hour = doseTime.getHour();
        if (hour >= 4 && hour < 11) {
            return MORNING;
        }
        if (hour >= 11 && hour < 16) {
            return LUNCH;
        }
        if (hour >= 16 && hour < 21) {
            return DINNER;
        }
        return BEDTIME;
    }

    /** 요청에 복용 시각이 없을 때 적용되는 기본 시각. */
    public LocalTime defaultTime() {
        return defaultTime;
    }

    /**
     * 알림 문구에 넣는 한글 표기("아침" 등).
     *
     * <p><b>서버가 만드는 발송 문구 전용</b>이다 — 푸시·문자 본문은 서버가 완성해서 보내야 하기 때문에
     * 여기에만 표시 문구를 둔다. 화면 표기는 여전히 프론트가 조립한다(서버는 API로 슬롯 코드만 준다).</p>
     */
    public String label() {
        return label;
    }
}

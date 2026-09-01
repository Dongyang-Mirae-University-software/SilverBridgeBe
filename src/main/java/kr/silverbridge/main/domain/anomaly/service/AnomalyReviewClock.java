package kr.silverbridge.main.domain.anomaly.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 재촉 판정의 시간 기준. <b>항상 KST</b>다.
 *
 * <p>서버·DB 타임존을 따르면 요약의 "하루"와 야간 억제 구간이 배포 환경마다 달라진다
 * (복약의 {@code MedicationClock}과 같은 이유). 테스트가 경계를 직접 지정할 수 있도록
 * 판정 함수는 시각을 인자로 받는 순수 함수로 둔다.</p>
 */
public final class AnomalyReviewClock {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private AnomalyReviewClock() {
    }

    public static OffsetDateTime now() {
        return OffsetDateTime.now(KST);
    }

    /** KST 기준 날짜. 요약의 "하루 1건" 축이다. */
    public static LocalDate toDate(OffsetDateTime time) {
        return time.atZoneSameInstant(KST).toLocalDate();
    }

    /** KST 기준 시각. 야간 억제·요약 시각 판정에 쓴다. */
    public static LocalTime toTime(OffsetDateTime time) {
        return time.atZoneSameInstant(KST).toLocalTime();
    }

    /**
     * 야간 억제 구간인지. {@code [quietStart, 자정) ∪ [자정, quietEnd)} 처럼 자정을 넘는 구간을 다룬다.
     *
     * <p>여기 걸린 건은 <b>버려지지 않고 다음 아침으로 미뤄진다</b> - 후보 조건이 그대로 남아
     * {@code quietEnd} 이후 첫 주기에 다시 잡힌다.</p>
     */
    public static boolean isQuietHours(LocalTime now, LocalTime quietStart, LocalTime quietEnd) {
        if (quietStart.equals(quietEnd)) {
            return false;   // 억제 없음
        }
        if (quietStart.isBefore(quietEnd)) {
            return !now.isBefore(quietStart) && now.isBefore(quietEnd);
        }
        // 자정을 넘는 구간(예: 22:00~08:00)
        return !now.isBefore(quietStart) || now.isBefore(quietEnd);
    }
}

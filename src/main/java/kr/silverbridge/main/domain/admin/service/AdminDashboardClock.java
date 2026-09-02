package kr.silverbridge.main.domain.admin.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 대시보드 집계의 시간 기준. <b>항상 KST</b>다.
 *
 * <p>"오늘 신규 가입", "오늘 접수된 문의", "오늘 이상감지"는 모두 관리자가 보는 하루이지 서버가 도는
 * 타임존의 하루가 아니다. 서버가 UTC면 09:00(KST) 이전 데이터가 전날로 밀려 아침마다 지표가
 * 되돌아간다({@code MedicationClock}·{@code AnomalyReviewClock}과 같은 이유).</p>
 *
 * <p>경계를 테스트가 직접 지정할 수 있도록 판정 함수는 기준 시각을 인자로 받는 순수 함수로 둔다.</p>
 */
public final class AdminDashboardClock {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private AdminDashboardClock() {
    }

    public static OffsetDateTime now() {
        return OffsetDateTime.now(KST);
    }

    /** KST 기준 날짜. 가입 추이의 날짜 축이다. */
    public static LocalDate toDate(OffsetDateTime time) {
        return time.atZoneSameInstant(KST).toLocalDate();
    }

    /** 그 시각이 속한 KST 하루의 00:00. "오늘" 집계의 하한이다. */
    public static OffsetDateTime startOfDay(OffsetDateTime time) {
        return toDate(time).atStartOfDay(KST).toOffsetDateTime();
    }

    /** 해당 KST 날짜의 00:00. */
    public static OffsetDateTime startOfDay(LocalDate date) {
        return date.atStartOfDay(KST).toOffsetDateTime();
    }
}

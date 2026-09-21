package kr.silverbridge.main.domain.anomaly.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.anomaly.service.AnomalyReviewClock;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;

/**
 * 관리자 이상감지 로그의 기간 필터. 기준은 상황의 <b>첫 감지 시각</b>({@code startedAt})이고 항상 KST다.
 *
 * <p>날짜 범위를 직접 받지 않고 전용 enum으로 받는다 - 잘못된 값은 400으로 거절되고(빈 결과로 오독되지 않게,
 * {@code WardListFilter}와 같은 판단), 경계 계산(주 시작 요일·월초)이 서버 한 곳에 모인다.</p>
 */
@Schema(description = "기간 필터 (생략 시 ALL) - TODAY 오늘 · THIS_WEEK 이번 주(월요일 시작) · THIS_MONTH 이번 달 · ALL 전체")
public enum AdminAnomalyPeriod {

    TODAY,
    THIS_WEEK,
    THIS_MONTH,
    ALL;

    /**
     * 이 기간의 하한(포함). {@link #ALL}이면 null - 호출부가 하한 없이 조회한다.
     *
     * @param now 기준 시각(테스트가 경계를 지정할 수 있도록 인자로 받는다)
     */
    public OffsetDateTime startFrom(OffsetDateTime now) {
        LocalDate today = AnomalyReviewClock.toDate(now);
        LocalDate start = switch (this) {
            case TODAY -> today;
            case THIS_WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case THIS_MONTH -> today.withDayOfMonth(1);
            case ALL -> null;
        };
        return start == null ? null : start.atStartOfDay(AnomalyReviewClock.KST).toOffsetDateTime();
    }

    public static AdminAnomalyPeriod orDefault(AdminAnomalyPeriod period) {
        return period == null ? ALL : period;
    }
}

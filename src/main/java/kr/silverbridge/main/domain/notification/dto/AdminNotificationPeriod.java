package kr.silverbridge.main.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 관리자 알림 이력의 기간 필터. 기준은 발송 시각이고 항상 KST다.
 *
 * <p>"전체"가 없는 이유 - 이력은 보관 기간(기본 90일)이 지나면 지워지므로 "전체"가 곧 "최근 90일"이 되어
 * 뜻이 흐려진다. 잘못된 값은 400이다(빈 목록으로 오독되지 않게).</p>
 */
@Schema(description = "기간 필터 (생략 시 LAST_7_DAYS) - TODAY 오늘 · LAST_7_DAYS 최근 7일(오늘 포함) · LAST_30_DAYS 최근 30일(오늘 포함)")
public enum AdminNotificationPeriod {

    TODAY(1),
    LAST_7_DAYS(7),
    LAST_30_DAYS(30);

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final int days;

    AdminNotificationPeriod(int days) {
        this.days = days;
    }

    /**
     * 이 기간의 하한(포함) - 오늘을 포함해 {@code days}일 전 KST 자정.
     *
     * @param now 기준 시각(테스트가 경계를 지정할 수 있도록 인자로 받는다)
     */
    public OffsetDateTime startFrom(OffsetDateTime now) {
        LocalDate today = now.atZoneSameInstant(KST).toLocalDate();
        return today.minusDays(days - 1L).atStartOfDay(KST).toOffsetDateTime();
    }

    public static AdminNotificationPeriod orDefault(AdminNotificationPeriod period) {
        return period == null ? LAST_7_DAYS : period;
    }
}

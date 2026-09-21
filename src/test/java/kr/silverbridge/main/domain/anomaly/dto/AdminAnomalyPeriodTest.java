package kr.silverbridge.main.domain.anomaly.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 이상감지 로그 기간 경계. 기준은 항상 KST이고 주는 월요일에 시작한다.
 * 서버가 UTC로 돌아도 09:00(KST) 이전 상황이 전날·전주로 밀리지 않아야 한다.
 */
class AdminAnomalyPeriodTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    /** 2026-09-23(수) 00:30 KST = 2026-09-22(화) 15:30 UTC - UTC 기준이면 날짜가 하루 어긋나는 시각. */
    private static final OffsetDateTime WED_EARLY_UTC = OffsetDateTime.of(2026, 9, 22, 15, 30, 0, 0, ZoneOffset.UTC);

    @Test
    @DisplayName("TODAY는 KST 그날 00:00부터다 - UTC로 계산하면 하루 앞으로 밀린다")
    void today() {
        assertThat(AdminAnomalyPeriod.TODAY.startFrom(WED_EARLY_UTC))
                .isEqualTo(OffsetDateTime.of(2026, 9, 23, 0, 0, 0, 0, KST));
    }

    @Test
    @DisplayName("THIS_WEEK는 그 주 월요일 00:00(KST)부터다")
    void thisWeek() {
        assertThat(AdminAnomalyPeriod.THIS_WEEK.startFrom(WED_EARLY_UTC))
                .isEqualTo(OffsetDateTime.of(2026, 9, 21, 0, 0, 0, 0, KST));
    }

    @Test
    @DisplayName("월요일이면 THIS_WEEK는 그날부터, 일요일 밤이면 6일 전 월요일부터다")
    void weekBoundary() {
        OffsetDateTime monday = OffsetDateTime.of(2026, 9, 21, 0, 0, 0, 0, KST);
        OffsetDateTime sundayNight = OffsetDateTime.of(2026, 9, 27, 23, 59, 0, 0, KST);

        assertThat(AdminAnomalyPeriod.THIS_WEEK.startFrom(monday)).isEqualTo(monday);
        assertThat(AdminAnomalyPeriod.THIS_WEEK.startFrom(sundayNight)).isEqualTo(monday);
    }

    @Test
    @DisplayName("THIS_MONTH는 그달 1일 00:00(KST)부터다 - 월말 UTC 시각도 KST 달로 판단한다")
    void thisMonth() {
        // 2026-09-30 15:30 UTC = 2026-10-01 00:30 KST
        OffsetDateTime firstOfOctoberKst = OffsetDateTime.of(2026, 9, 30, 15, 30, 0, 0, ZoneOffset.UTC);

        assertThat(AdminAnomalyPeriod.THIS_MONTH.startFrom(WED_EARLY_UTC))
                .isEqualTo(OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, KST));
        assertThat(AdminAnomalyPeriod.THIS_MONTH.startFrom(firstOfOctoberKst))
                .isEqualTo(OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, KST));
    }

    @Test
    @DisplayName("ALL은 하한이 없고, 생략하면 ALL이다")
    void all() {
        assertThat(AdminAnomalyPeriod.ALL.startFrom(WED_EARLY_UTC)).isNull();
        assertThat(AdminAnomalyPeriod.orDefault(null)).isEqualTo(AdminAnomalyPeriod.ALL);
    }
}

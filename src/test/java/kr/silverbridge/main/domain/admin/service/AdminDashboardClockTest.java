package kr.silverbridge.main.domain.admin.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대시보드 "오늘"의 경계 검증.
 *
 * <p>서버가 UTC로 돌면 09:00(KST) 이전 데이터가 전날로 밀려 아침마다 지표가 되돌아간다.
 * 그래서 날짜·하루 시작을 <b>서버 타임존과 무관하게</b> KST로 고정한다.</p>
 */
class AdminDashboardClockTest {

    @Test
    @DisplayName("UTC 시각이라도 KST 날짜로 판정한다 - 자정 직후가 전날로 밀리지 않는다")
    void utc_시각을_KST_날짜로() {
        // 2026-09-01T15:30Z == 2026-09-02T00:30+09:00 → KST로는 이미 9월 2일이다
        OffsetDateTime utcInstant = OffsetDateTime.of(2026, 9, 1, 15, 30, 0, 0, ZoneOffset.UTC);

        assertThat(AdminDashboardClock.toDate(utcInstant)).isEqualTo(LocalDate.of(2026, 9, 2));
    }

    @Test
    @DisplayName("KST 자정 직전은 아직 그 전날이다")
    void 자정_직전은_전날() {
        OffsetDateTime justBeforeMidnight =
                OffsetDateTime.of(2026, 9, 2, 23, 59, 59, 0, ZoneOffset.ofHours(9));

        assertThat(AdminDashboardClock.toDate(justBeforeMidnight)).isEqualTo(LocalDate.of(2026, 9, 2));
    }

    @Test
    @DisplayName("하루 시작은 KST 00:00 - 오늘 집계의 하한이다")
    void 하루_시작은_KST_자정() {
        OffsetDateTime midday = OffsetDateTime.of(2026, 9, 2, 13, 0, 0, 0, ZoneOffset.ofHours(9));

        OffsetDateTime start = AdminDashboardClock.startOfDay(midday);

        assertThat(start).isEqualTo(OffsetDateTime.of(2026, 9, 2, 0, 0, 0, 0, ZoneOffset.ofHours(9)));
        // 같은 순간을 UTC로 보면 전날 15:00이다 - 이 경계를 서버 타임존으로 잡으면 하루가 어긋난다
        assertThat(start.withOffsetSameInstant(ZoneOffset.UTC).getHour()).isEqualTo(15);
    }

    @Test
    @DisplayName("UTC 시각으로 하루 시작을 구해도 KST 자정이 된다")
    void utc_입력이어도_KST_자정() {
        OffsetDateTime utcInstant = OffsetDateTime.of(2026, 9, 1, 15, 30, 0, 0, ZoneOffset.UTC);

        assertThat(AdminDashboardClock.startOfDay(utcInstant))
                .isEqualTo(OffsetDateTime.of(2026, 9, 2, 0, 0, 0, 0, ZoneOffset.ofHours(9)));
    }
}

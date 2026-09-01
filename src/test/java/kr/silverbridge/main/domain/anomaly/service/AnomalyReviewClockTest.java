package kr.silverbridge.main.domain.anomaly.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 야간 억제 구간 판정. 22:00~08:00처럼 <b>자정을 넘는</b> 구간이라 경계를 리터럴로 고정한다.
 *
 * <p>여기서 억제된 재촉은 버려지지 않고 다음 아침으로 미뤄진다 - 후보 조건이 그대로 남기 때문이다.</p>
 */
class AnomalyReviewClockTest {

    private static final LocalTime QUIET_START = LocalTime.of(22, 0);
    private static final LocalTime QUIET_END = LocalTime.of(8, 0);

    private boolean quiet(int hour, int minute) {
        return AnomalyReviewClock.isQuietHours(LocalTime.of(hour, minute), QUIET_START, QUIET_END);
    }

    @Test
    @DisplayName("억제 시작 시각(22:00)은 억제 구간에 포함된다")
    void quietStartIsInclusive() {
        assertThat(quiet(22, 0)).isTrue();
    }

    @Test
    @DisplayName("자정을 넘어 새벽에도 억제된다")
    void afterMidnightIsQuiet() {
        assertThat(quiet(0, 0)).isTrue();
        assertThat(quiet(3, 30)).isTrue();
        assertThat(quiet(7, 59)).isTrue();
    }

    @Test
    @DisplayName("억제 종료 시각(08:00)부터 다시 보낸다 - 밤새 밀린 재촉이 아침 첫 주기에 나간다")
    void quietEndIsExclusive() {
        assertThat(quiet(8, 0)).isFalse();
    }

    @Test
    @DisplayName("낮 시간은 억제되지 않는다")
    void daytimeIsNotQuiet() {
        assertThat(quiet(8, 1)).isFalse();
        assertThat(quiet(14, 0)).isFalse();
        assertThat(quiet(20, 0)).isFalse();
        assertThat(quiet(21, 59)).isFalse();
    }

    @Test
    @DisplayName("시작과 끝이 같으면 억제하지 않는다 - 설정으로 야간 억제를 끌 수 있어야 한다")
    void sameStartAndEndDisablesSuppression() {
        assertThat(AnomalyReviewClock.isQuietHours(LocalTime.of(3, 0), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT))
                .isFalse();
    }
}

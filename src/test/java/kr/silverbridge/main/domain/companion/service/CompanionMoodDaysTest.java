package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.entity.CompanionMood;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static kr.silverbridge.main.domain.companion.service.CompanionTestSessions.finished;
import static org.assertj.core.api.Assertions.assertThat;

/** 하루 기분·연속 일수 규칙(2026-11-04 사용자 결정 1·2). */
class CompanionMoodDaysTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);
    private static final String W = "CW0001";

    @Test
    @DisplayName("하루 기분은 그날 마지막으로 판정된 대화 - 판단 불가(null)는 건너뛴다")
    void dailyMoodIsLastJudged() {
        Map<LocalDate, CompanionMood> moods = CompanionMoodDays.dailyMoods(List.of(
                finished(W, TODAY, CompanionSlot.S09, CompanionMood.JOY, 3),
                finished(W, TODAY, CompanionSlot.S15, CompanionMood.WORRY, 3),
                finished(W, TODAY, CompanionSlot.S21, null, 1)));

        assertThat(moods).containsEntry(TODAY, CompanionMood.WORRY).hasSize(1);
    }

    @Test
    @DisplayName("걱정·우울이 섞여도 이어진 것으로 세고, 보여 줄 기분은 마지막 날 기분이다")
    void mixedConcerningDaysCount() {
        Map<LocalDate, CompanionMood> moods = Map.of(
                TODAY, CompanionMood.DEPRESSED,
                TODAY.minusDays(1), CompanionMood.WORRY,
                TODAY.minusDays(2), CompanionMood.WORRY,
                TODAY.minusDays(3), CompanionMood.CALM);

        CompanionMoodDays.Streak streak = CompanionMoodDays.streak(moods, TODAY).orElseThrow();

        assertThat(streak.days()).isEqualTo(3);
        assertThat(streak.mood()).isEqualTo(CompanionMood.DEPRESSED);
        assertThat(streak.reachesNotice()).isTrue();
    }

    @Test
    @DisplayName("대화가 없는 날은 연속을 끊는다")
    void missingDayBreaks() {
        Map<LocalDate, CompanionMood> moods = Map.of(
                TODAY, CompanionMood.WORRY,
                TODAY.minusDays(1), CompanionMood.WORRY,
                TODAY.minusDays(3), CompanionMood.WORRY);

        assertThat(CompanionMoodDays.streak(moods, TODAY).orElseThrow().days()).isEqualTo(2);
    }

    @Test
    @DisplayName("대화는 했지만 모두 판단 불가(UNKNOWN → null)인 날도 연속을 끊는다(2026-11-09 사용자 확정 A)")
    void unknownOnlyDayBreaks() {
        Map<LocalDate, CompanionMood> moods = CompanionMoodDays.dailyMoods(List.of(
                finished(W, TODAY, CompanionSlot.S09, CompanionMood.WORRY, 3),
                finished(W, TODAY.minusDays(1), CompanionSlot.S09, null, 2),
                finished(W, TODAY.minusDays(1), CompanionSlot.S21, null, 1),
                finished(W, TODAY.minusDays(2), CompanionSlot.S09, CompanionMood.WORRY, 3),
                finished(W, TODAY.minusDays(3), CompanionSlot.S09, CompanionMood.DEPRESSED, 3)));

        assertThat(moods).doesNotContainKey(TODAY.minusDays(1));
        CompanionMoodDays.Streak streak = CompanionMoodDays.streak(moods, TODAY).orElseThrow();
        assertThat(streak.days()).isEqualTo(1);
        assertThat(streak.reachesNotice()).isFalse();
    }

    @Test
    @DisplayName("오늘 아직 판정된 대화가 없으면 어제를 기준일로 센다, 오늘이 기쁨이면 이어지지 않는다")
    void baseDay() {
        Map<LocalDate, CompanionMood> yesterdayBased = Map.of(
                TODAY.minusDays(1), CompanionMood.WORRY,
                TODAY.minusDays(2), CompanionMood.WORRY,
                TODAY.minusDays(3), CompanionMood.DEPRESSED);
        assertThat(CompanionMoodDays.streak(yesterdayBased, TODAY).orElseThrow().days()).isEqualTo(3);

        Map<LocalDate, CompanionMood> brokenToday = Map.of(
                TODAY, CompanionMood.JOY,
                TODAY.minusDays(1), CompanionMood.WORRY,
                TODAY.minusDays(2), CompanionMood.WORRY);
        assertThat(CompanionMoodDays.streak(brokenToday, TODAY)).isEmpty();
    }
}

package kr.silverbridge.main.domain.checkin.service;

import kr.silverbridge.main.domain.checkin.entity.CheckinEmotion;
import kr.silverbridge.main.domain.checkin.entity.CheckinEndReason;
import kr.silverbridge.main.domain.checkin.entity.CheckinResult;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;
import kr.silverbridge.main.domain.checkin.service.CheckinAttentionJudge.Current;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckinAttentionJudgeTest {

    private static Current current(String sleep, String meal, String pain, CheckinEmotion emotion,
                                   CheckinEmotion self, Double answerSec) {
        return new Current(false, emotion, self, sleep, meal, pain, null, null, answerSec, null);
    }

    private static CheckinResult past(String sleep, String meal, CheckinEmotion emotion, Double answerSec) {
        return CheckinResult.builder().slot(CheckinSlot.AT_09).checkDate(LocalDate.of(2026, 10, 20))
                .emotion(emotion).sleep(sleep).meal(meal).avgAnswerSec(answerSec)
                .endReason(CheckinEndReason.USER_END).build();
    }

    private static List<CheckinResult> calmHistory(int n) {
        List<CheckinResult> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(past("GOOD", "ATE", CheckinEmotion.CALM, 1.0 + (i % 3) * 0.1));
        }
        return list;
    }

    @Test
    @DisplayName("신호 하나만으로는 붙이지 않는다(잠 나쁨 하나)")
    void singleSignalNever() {
        var v = CheckinAttentionJudge.judge(current("BAD", null, null, CheckinEmotion.CALM, null, null), calmHistory(10));
        assertThat(v.attention()).isFalse();
        assertThat(v.reasons()).containsExactly("SLEEP_UNUSUAL");
    }

    @Test
    @DisplayName("평소와 다른 생활 신호 둘이면 확인 필요")
    void twoLifeSignals() {
        var v = CheckinAttentionJudge.judge(current("BAD", "SKIPPED", null, CheckinEmotion.CALM, null, null), calmHistory(10));
        assertThat(v.attention()).isTrue();
        assertThat(v.reasons()).containsExactly("SLEEP_UNUSUAL", "MEAL_UNUSUAL");
    }

    @Test
    @DisplayName("평소에도 자주 잠이 나쁜 분이면 잠 나쁨은 근거가 아니다")
    void usualForThisPerson() {
        List<CheckinResult> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(past(i < 6 ? "BAD" : "GOOD", "ATE", CheckinEmotion.CALM, null));
        }
        var v = CheckinAttentionJudge.judge(current("BAD", "SKIPPED", null, CheckinEmotion.CALM, null, null), history);
        assertThat(v.reasons()).containsExactly("MEAL_UNUSUAL");
        assertThat(v.attention()).isFalse();
    }

    @Test
    @DisplayName("목소리·정서 보조 근거만으로는 붙이지 않는다(합 1.0)")
    void auxiliaryOnly() {
        var v = CheckinAttentionJudge.judge(current(null, null, null, CheckinEmotion.WORRIED, null, 9.0), calmHistory(10));
        assertThat(v.reasons()).containsExactly("EMOTION_UNUSUAL", "VOICE_CHANGE");
        assertThat(v.attention()).isFalse();
    }

    @Test
    @DisplayName("강한 근거 하나 + 보조 둘(합 2.0, 근거 3개)이면 확인 필요")
    void strongPlusAuxiliary() {
        var v = CheckinAttentionJudge.judge(current(null, null, "YES", CheckinEmotion.LOW_ENERGY, null, 9.0), calmHistory(10));
        assertThat(v.reasons()).containsExactly("PAIN_UNUSUAL", "EMOTION_UNUSUAL", "VOICE_CHANGE");
        assertThat(v.attention()).isTrue();
    }

    @Test
    @DisplayName("본인이 고른 부정 기분은 강한 근거")
    void selfReportStrong() {
        var v = CheckinAttentionJudge.judge(current("BAD", null, null, CheckinEmotion.CALM, CheckinEmotion.LONELY, null),
                calmHistory(10));
        assertThat(v.reasons()).containsExactly("SLEEP_UNUSUAL", "SELF_REPORT_NEGATIVE");
        assertThat(v.attention()).isTrue();
    }

    @Test
    @DisplayName("위험 신호면 다른 근거 없이도 확인 필요")
    void riskAlone() {
        var v = CheckinAttentionJudge.judge(new Current(true, CheckinEmotion.CALM, null, null, null, null, null, null,
                null, null), List.of());
        assertThat(v.attention()).isTrue();
        assertThat(v.reasons()).containsExactly("RISK");
    }

    @Test
    @DisplayName("기준이 3건 미만이면 평소를 모르므로 부정 값을 그대로 근거로 본다 / 목소리는 5건 미만이면 보지 않는다")
    void thinBaseline() {
        var v = CheckinAttentionJudge.judge(current("BAD", "SKIPPED", null, CheckinEmotion.CALM, null, 30.0), calmHistory(2));
        assertThat(v.reasons()).containsExactly("SLEEP_UNUSUAL", "MEAL_UNUSUAL");
        assertThat(v.attention()).isTrue();
    }
}

package kr.silverbridge.main.domain.checkin.service;

import kr.silverbridge.main.domain.checkin.entity.CheckinEmotion;
import kr.silverbridge.main.domain.checkin.entity.CheckinResult;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 평소 대비 "확인 필요" 판정(2026-11-01, 2차). 최근 {@code baselineDays}일 결과를 기준값으로 삼는다.
 *
 * <p><b>신호 하나만으로는 붙이지 않는다</b>: 근거가 둘 이상이고 점수가 2.0 이상이어야 한다. 위험(응급·자해)만 예외로 곧바로 붙는다.</p>
 * <ul>
 *   <li>생활 신호(잠 나쁨·식사 거름·통증 있음·기분 나쁨·외로움 있음)가 평소와 다르면 각 1.0 - 강한 근거</li>
 *   <li>본인이 고른 기분이 부정(외로움·기운 없음·걱정)이고 평소와 다르면 1.0 - 강한 근거</li>
 *   <li>AI 정서가 부정이고 평소와 다르면 0.5 - 보통</li>
 *   <li>목소리 측정값(대답까지 걸린 시간 늘어남·말 빠르기 느려짐)이 평소의 2표준편차 밖이면 0.5 - 보조(혼자서는 절대 못 붙인다)</li>
 *   <li>표정은 AI가 아직 내지 않아(null) 쓰지 않는다</li>
 * </ul>
 * <p>"평소와 다르다" = 그 신호의 기준 기간 부정 비율이 30% 이하(본인 기분은 50% 이하). 기준 관측이 3건 미만이면 평소를
 * 모르므로 부정 값을 그대로 다르다고 본다(처음 쓰는 어르신도 둘 이상 겹치면 알린다). 목소리는 기준 5건 이상일 때만 본다.</p>
 */
public final class CheckinAttentionJudge {

    static final double THRESHOLD = 2.0;
    static final int MIN_REASONS = 2;
    static final int MIN_BASELINE = 3;
    static final int MIN_VOICE_BASELINE = 5;
    static final double USUAL_NEGATIVE_RATE = 0.3;
    static final double USUAL_SELF_NEGATIVE_RATE = 0.5;

    private static final Set<CheckinEmotion> NEGATIVE = EnumSet.of(
            CheckinEmotion.LONELY, CheckinEmotion.LOW_ENERGY, CheckinEmotion.WORRIED);
    private static final Map<Reason, Signal> LIFE = Map.of(
            Reason.SLEEP_UNUSUAL, new Signal(CheckinResult::getSleep, "BAD"),
            Reason.MEAL_UNUSUAL, new Signal(CheckinResult::getMeal, "SKIPPED"),
            Reason.PAIN_UNUSUAL, new Signal(CheckinResult::getPain, "YES"),
            Reason.MOOD_UNUSUAL, new Signal(CheckinResult::getMood, "BAD"),
            Reason.LONELY_UNUSUAL, new Signal(CheckinResult::getLonely, "YES"));

    private CheckinAttentionJudge() {
    }

    /** 근거 고정 코드. 보호자 화면 문구는 FE가 코드로 정한다. */
    public enum Reason {
        RISK, SLEEP_UNUSUAL, MEAL_UNUSUAL, PAIN_UNUSUAL, MOOD_UNUSUAL, LONELY_UNUSUAL,
        SELF_REPORT_NEGATIVE, EMOTION_UNUSUAL, VOICE_CHANGE
    }

    public record Verdict(boolean attention, List<String> reasons) {
    }

    private record Signal(Function<CheckinResult, String> getter, String negative) {
    }

    /** 현재 값과 기준 기간 결과로 판정한다. 근거 목록은 확인 필요가 아니어도 채워 둔다(보호자 화면 참고용). */
    public static Verdict judge(Current current, List<CheckinResult> baseline) {
        List<Reason> reasons = new ArrayList<>();
        double score = 0;
        if (current.risk()) {
            reasons.add(Reason.RISK);
        }
        for (Reason reason : Reason.values()) {
            Signal signal = LIFE.get(reason);
            if (signal == null || !signal.negative().equals(current.signal(reason))) {
                continue;
            }
            if (unusual(baseline.stream().map(signal.getter()).filter(Objects::nonNull).toList(),
                    signal.negative()::equals, USUAL_NEGATIVE_RATE)) {
                reasons.add(reason);
                score += 1.0;
            }
        }
        if (current.selfReport() != null && NEGATIVE.contains(current.selfReport())
                && unusual(baseline.stream().map(CheckinResult::getSelfReport).filter(Objects::nonNull).toList(),
                NEGATIVE::contains, USUAL_SELF_NEGATIVE_RATE)) {
            reasons.add(Reason.SELF_REPORT_NEGATIVE);
            score += 1.0;
        }
        if (current.emotion() != null && NEGATIVE.contains(current.emotion())
                && unusual(baseline.stream().map(CheckinResult::getEmotion)
                        .filter(e -> e != null && e != CheckinEmotion.UNDECIDED).toList(),
                NEGATIVE::contains, USUAL_NEGATIVE_RATE)) {
            reasons.add(Reason.EMOTION_UNUSUAL);
            score += 0.5;
        }
        if (voiceChanged(current, baseline)) {
            reasons.add(Reason.VOICE_CHANGE);
            score += 0.5;
        }
        boolean attention = current.risk()
                || (score >= THRESHOLD && reasons.size() >= MIN_REASONS);
        return new Verdict(attention, reasons.stream().map(Reason::name).toList());
    }

    private static <T> boolean unusual(List<T> observed, java.util.function.Predicate<T> negative, double usualRate) {
        if (observed.size() < MIN_BASELINE) {
            return true;
        }
        long negatives = observed.stream().filter(negative).count();
        return (double) negatives / observed.size() <= usualRate;
    }

    /** 대답까지 걸린 시간이 평소보다 2표준편차 이상 길거나, 말 빠르기가 2표준편차 이상 느림. 표준편차는 하한을 둔다. */
    private static boolean voiceChanged(Current current, List<CheckinResult> baseline) {
        return outside(current.avgAnswerSec(), baseline.stream().map(CheckinResult::getAvgAnswerSec).toList(), 0.3, true)
                || outside(current.avgCharsPerSec(), baseline.stream().map(CheckinResult::getAvgCharsPerSec).toList(),
                0.3, false);
    }

    private static boolean outside(Double value, List<Double> history, double minSd, boolean higherIsWorse) {
        List<Double> values = history.stream().filter(Objects::nonNull).toList();
        if (value == null || values.size() < MIN_VOICE_BASELINE) {
            return false;
        }
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / values.size();
        double sd = Math.max(Math.sqrt(variance), minSd);
        return higherIsWorse ? value > mean + 2 * sd : value < mean - 2 * sd;
    }

    /** 판정 대상(방금 정리한 결과의 값). */
    public record Current(boolean risk, CheckinEmotion emotion, CheckinEmotion selfReport, String sleep, String meal,
                          String pain, String mood, String lonely, Double avgAnswerSec, Double avgCharsPerSec) {

        String signal(Reason reason) {
            return switch (reason) {
                case SLEEP_UNUSUAL -> sleep;
                case MEAL_UNUSUAL -> meal;
                case PAIN_UNUSUAL -> pain;
                case MOOD_UNUSUAL -> mood;
                case LONELY_UNUSUAL -> lonely;
                default -> null;
            };
        }
    }
}

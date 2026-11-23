package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.entity.CompanionMood;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 날짜별 기분과 걱정·우울 연속 일수(2026-11-04 사용자 결정, PR-B).
 *
 * <ul>
 *   <li><b>하루 기분</b> = 그날 모든 대화의 판정된 턴 기분 중 가장 많이 나온 기분(2026-11-23 사용자 결정, 전에는 마지막 대화의 기분).
 *       판단 불가(null)는 건너뛴다.</li>
 *   <li><b>연속 일수</b> = 기준일부터 거꾸로 하루 기분이 걱정·우울인 날 수. 대화가 없는(판정된 대화가 없는) 날은
 *       연속을 끊는다 - 관찰하지 못한 날을 "이어졌다"고 볼 수 없다.</li>
 *   <li><b>기준일</b> = 오늘 판정된 대화가 있으면 오늘, 없으면 어제(오늘은 아직 끝나지 않았다).</li>
 * </ul>
 * 걱정과 우울이 섞여도 이어진 것으로 센다. 보여 줄 기분은 기준일의 기분이다.
 */
public final class CompanionMoodDays {

    /** 주의 알림·확인할 일을 띄우는 연속 일수(결정 I). */
    public static final int NOTICE_DAYS = 3;
    /** 연속 일수를 볼 때 거꾸로 읽는 최대 일수. */
    public static final int LOOKBACK_DAYS = 30;

    private CompanionMoodDays() {
    }

    /** 걱정·우울이 이어진 결과. 이어지지 않으면 {@link Optional#empty()}. */
    public record Streak(CompanionMood mood, int days, LocalDate lastDate) {
        public boolean reachesNotice() {
            return days >= NOTICE_DAYS;
        }
    }

    /**
     * 보호자가 볼 수 있는 대화만 남긴다 - 지금 ACTIVE 연결이 맺어진 뒤 시작한 대화(D-1, 2026-10-31 사용자 결정).
     * 보호자 화면 주의 칸과 주의 알림이 같은 기준을 쓰도록 이 한 곳에 둔다(2026-11-09 PR-B 점검 M-1).
     */
    public static List<CompanionSession> startedSince(Collection<CompanionSession> sessions, OffsetDateTime connectedAt) {
        return sessions.stream().filter(s -> !s.getStartedAt().isBefore(connectedAt)).toList();
    }

    /**
     * 마친 대화들 → 날짜별 하루 기분(판정된 턴이 있는 날만).
     *
     * <p>그날 마친 모든 대화의 판정된 턴 기분 중 <b>가장 많이 나온 기분</b>(2026-11-23 사용자 결정 - 마지막 대화 1번으로 정하면
     * 대화 한 번의 짧은 발화가 하루를 대표해 버렸다). 같은 수면 우울 > 걱정 > 평온 > 기쁨 순으로 고른다(걱정·우울을 놓치지 않게).
     * 턴 기록이 없는 대화(턴 저장 V71 이전)는 대화 기분을 한 표로 센다.</p>
     *
     * @param turnMoods 대화 ID → 판정된 턴 기분들({@link CompanionTurnMoodReader})
     */
    public static Map<LocalDate, CompanionMood> dailyMoods(Collection<CompanionSession> finished,
                                                         Map<Long, List<CompanionMood>> turnMoods) {
        Map<LocalDate, EnumMap<CompanionMood, Integer>> counts = new HashMap<>();
        for (CompanionSession session : finished) {
            List<CompanionMood> moods = turnMoods.getOrDefault(session.getId(), List.of());
            if (moods.isEmpty() && session.getMood() != null) {
                moods = List.of(session.getMood());
            }
            for (CompanionMood mood : moods) {
                counts.computeIfAbsent(session.getSessionDate(), d -> new EnumMap<>(CompanionMood.class))
                        .merge(mood, 1, Integer::sum);
            }
        }
        Map<LocalDate, CompanionMood> daily = new HashMap<>();
        counts.forEach((date, byMood) -> byMood.entrySet().stream()
                .max(Comparator.<Map.Entry<CompanionMood, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparingInt(e -> e.getKey().ordinal()))
                .ifPresent(top -> daily.put(date, top.getKey())));
        return daily;
    }

    public static Optional<Streak> streak(Map<LocalDate, CompanionMood> dailyMoods, LocalDate today) {
        LocalDate end = dailyMoods.containsKey(today) ? today : today.minusDays(1);
        CompanionMood endMood = dailyMoods.get(end);
        if (!isConcerning(endMood)) {
            return Optional.empty();
        }
        int days = 0;
        LocalDate day = end;
        while (days < LOOKBACK_DAYS && isConcerning(dailyMoods.get(day))) {
            days++;
            day = day.minusDays(1);
        }
        return Optional.of(new Streak(endMood, days, end));
    }

    static boolean isConcerning(CompanionMood mood) {
        return mood == CompanionMood.WORRY || mood == CompanionMood.DEPRESSED;
    }
}

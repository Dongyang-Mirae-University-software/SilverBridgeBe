package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.entity.CompanionMood;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 날짜별 기분과 걱정·우울 연속 일수(2026-11-04 사용자 결정, PR-B).
 *
 * <ul>
 *   <li><b>하루 기분</b> = 그날 마지막으로 판정된 대화의 기분. 판단 불가(null)는 건너뛴다.</li>
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

    /** 마친 대화들 → 날짜별 하루 기분(판정된 대화가 있는 날만). */
    public static Map<LocalDate, CompanionMood> dailyMoods(Collection<CompanionSession> finished) {
        Map<LocalDate, CompanionSession> last = new HashMap<>();
        Comparator<CompanionSession> order = Comparator
                .comparing(CompanionSession::getEndedAt, Comparator.nullsFirst(OffsetDateTime::compareTo))
                .thenComparing(CompanionSession::getId, Comparator.nullsFirst(Long::compareTo));
        for (CompanionSession session : finished) {
            if (session.getMood() == null) {
                continue;
            }
            last.merge(session.getSessionDate(), session, (a, b) -> order.compare(a, b) >= 0 ? a : b);
        }
        Map<LocalDate, CompanionMood> moods = new HashMap<>();
        last.forEach((date, session) -> moods.put(date, session.getMood()));
        return moods;
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

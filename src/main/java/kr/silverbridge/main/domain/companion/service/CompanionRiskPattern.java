package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.OptionalInt;
import java.util.Set;

/**
 * 여러 날 위험 누적 판정(2026-11-11 사용자 결정). 한 번의 대답으로는 riskFlag에 못 미치지만 위험한 말이 며칠에 걸쳐
 * 나오는 경우를 잡는다.
 *
 * <ul>
 *   <li><b>높은 날</b> = 최근 {@code windowDays}일(기준일 포함, {@code session_date}) 중 대화의 {@code max_risk_prob}가
 *       {@code threshold} 이상인 날. 같은 날 여러 번은 1일이다. 대화 상태·테스트 대화 여부는 가리지 않는다(정책).</li>
 *   <li>높은 날이 {@code minDays}일 이상이고, 그중 <b>위험 알림({@code COMPANION_RISK})이 나가지 않은 날이 1일 이상</b>
 *       있어야 한다 - 이미 위험 알림을 받은 날만으로는 보내지 않는다.</li>
 * </ul>
 * 보호자별 D-1 필터({@link CompanionMoodDays#startedSince})는 호출하는 쪽이 먼저 건다.
 */
public final class CompanionRiskPattern {

    private CompanionRiskPattern() {
    }

    /** 조건을 채우면 높은 날 수, 아니면 비어 있음. */
    public static OptionalInt qualifyingDays(Collection<CompanionSession> sessions, LocalDate today,
                                             CompanionProperties.RiskPattern config) {
        LocalDate from = windowStart(today, config);
        Set<LocalDate> highDays = new HashSet<>();
        Set<LocalDate> alertedDays = new HashSet<>();
        for (CompanionSession session : sessions) {
            LocalDate date = session.getSessionDate();
            if (date.isBefore(from) || date.isAfter(today)) {
                continue;
            }
            if (isHigh(session, config)) {
                highDays.add(date);
            }
            if (session.getRiskNotifiedAt() != null) {
                alertedDays.add(date);
            }
        }
        if (highDays.size() < config.getMinDays() || alertedDays.containsAll(highDays)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(highDays.size());
    }

    /** 판정 기간의 첫날(기준일 포함 {@code windowDays}일). */
    public static LocalDate windowStart(LocalDate today, CompanionProperties.RiskPattern config) {
        return today.minusDays(Math.max(1, config.getWindowDays()) - 1L);
    }

    static boolean isHigh(CompanionSession session, CompanionProperties.RiskPattern config) {
        return session.getMaxRiskProb() != null && session.getMaxRiskProb().compareTo(config.getThreshold()) >= 0;
    }
}

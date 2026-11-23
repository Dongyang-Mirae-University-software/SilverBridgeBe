package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.entity.CompanionMood;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.repository.CompanionTurnRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 하루 기분 계산({@link CompanionMoodDays#dailyMoods})에 넘길 대화별 턴 기분을 한 번에 읽는다(2026-11-23).
 * 대화마다 따로 읽지 않도록 대화 ID 묶음으로 한 번만 조회한다.
 */
@Component
@RequiredArgsConstructor
public class CompanionTurnMoodReader {

    private final CompanionTurnRepository turnRepository;

    public Map<Long, List<CompanionMood>> bySession(Collection<CompanionSession> sessions) {
        List<Long> ids = sessions.stream().map(CompanionSession::getId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<CompanionMood>> moods = new HashMap<>();
        for (CompanionTurnRepository.TurnMood row : turnRepository.findMoodsBySessionIdIn(ids)) {
            moods.computeIfAbsent(row.getSessionId(), id -> new ArrayList<>()).add(row.getMood());
        }
        return moods;
    }

    /** 대화들 → 날짜별 하루 기분(턴 기분을 읽어 {@link CompanionMoodDays#dailyMoods}에 넘긴다). */
    public Map<LocalDate, CompanionMood> dailyMoods(Collection<CompanionSession> finished) {
        return CompanionMoodDays.dailyMoods(finished, bySession(finished));
    }
}

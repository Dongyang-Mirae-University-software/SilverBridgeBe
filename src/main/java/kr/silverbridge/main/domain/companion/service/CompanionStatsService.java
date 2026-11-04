package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.dto.CompanionAdminStatsResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionAdminStatsResponse.ContinuingWard;
import kr.silverbridge.main.domain.companion.dto.CompanionAdminStatsResponse.MoodDistribution;
import kr.silverbridge.main.domain.companion.dto.CompanionAdminStatsResponse.NoDialogueWard;
import kr.silverbridge.main.domain.companion.entity.CompanionMood;
import kr.silverbridge.main.domain.companion.entity.CompanionPromptLog;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSessionStatus;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.repository.CompanionNoticeLogRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionPromptLogRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 관리자 안부 말벗 오늘 집계(2026-11-04, PR-B). AI가 아니라 BE에 쌓인 대화 결과로 계산한다(노션 카드 "AI 통계 집계 API").
 *
 * <ul>
 *   <li>응답률 = 오늘 보낸 질문 안내 중 그 (피보호자, 시간대) 대화에 대답이 1번 이상 있는 비율. 안내가 없으면 null.</li>
 *   <li>기분 분포 = 어르신별 오늘 마지막으로 마친 대화의 기분(판단 불가는 unknown으로 따로).</li>
 *   <li>이어지는 어르신 = {@link CompanionMoodDays} 규칙(연속 3일 이상).</li>
 *   <li>알림 수 = 주의 알림 기록 + 위험 알림을 보낸 대화 수(오늘).</li>
 * </ul>
 * 원문·요약은 다루지 않는다. 조회 전용이라 감사 로그를 남기지 않는다.
 */
@Service
@RequiredArgsConstructor
public class CompanionStatsService {

    private final CompanionService companionService;
    private final CompanionSessionRepository sessionRepository;
    private final CompanionPromptLogRepository promptLogRepository;
    private final CompanionNoticeLogRepository noticeLogRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public CompanionAdminStatsResponse today() {
        ZonedDateTime now = companionService.now();
        LocalDate date = now.toLocalDate();

        List<CompanionPromptLog> prompts = promptLogRepository.findByPromptDate(date);
        List<CompanionSession> todaySessions = sessionRepository.findBySessionDate(date);
        Set<String> answeredKeys = todaySessions.stream()
                .filter(s -> s.getAnsweredCount() != null && s.getAnsweredCount() > 0)
                .map(s -> key(s.getWardId(), s.getSlot()))
                .collect(Collectors.toSet());
        long answeredPrompts = prompts.stream().filter(p -> answeredKeys.contains(key(p.getWardId(), p.getSlot()))).count();
        Integer responseRate = prompts.isEmpty() ? null
                : (int) Math.round(answeredPrompts * 100.0 / prompts.size());

        Set<String> answeredWards = todaySessions.stream()
                .filter(s -> s.getAnsweredCount() != null && s.getAnsweredCount() > 0)
                .map(CompanionSession::getWardId).collect(Collectors.toSet());
        Set<String> silentWards = prompts.stream().map(CompanionPromptLog::getWardId)
                .filter(id -> !answeredWards.contains(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        OffsetDateTime dayStart = date.atStartOfDay(CompanionService.KST).toOffsetDateTime();
        long alertSent = noticeLogRepository.countByNoticeDate(date)
                + sessionRepository.countRiskNotifiedBetween(dayStart, dayStart.plusDays(1));

        MoodDistribution distribution = distribution(todaySessions);

        Map<String, List<CompanionSession>> recentByWard = sessionRepository
                .findAllFinishedSince(CompanionSessionStatus.FINISHED, date.minusDays(CompanionMoodDays.LOOKBACK_DAYS))
                .stream().collect(Collectors.groupingBy(CompanionSession::getWardId));
        // 피보호자 ID → 연속 결과(같은 일수·기분인 두 사람이 섞이지 않게 ID로 묶는다)
        Map<String, CompanionMoodDays.Streak> streaks = new HashMap<>();
        recentByWard.forEach((wardId, sessions) -> CompanionMoodDays.streak(CompanionMoodDays.dailyMoods(sessions), date)
                .filter(CompanionMoodDays.Streak::reachesNotice)
                .ifPresent(streak -> streaks.put(wardId, streak)));

        Set<String> nameIds = new HashSet<>(silentWards);
        nameIds.addAll(streaks.keySet());
        Map<String, String> names = userRepository.findAllById(nameIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName));

        List<ContinuingWard> continuing = new ArrayList<>(streaks.entrySet().stream()
                .map(e -> new ContinuingWard(e.getKey(), names.get(e.getKey()), e.getValue().mood().name(),
                        e.getValue().days()))
                .toList());
        continuing.sort(Comparator.comparingInt(ContinuingWard::days).reversed()
                .thenComparing(ContinuingWard::wardId));

        Map<String, OffsetDateTime> lastAnswered = new HashMap<>();
        if (!silentWards.isEmpty()) {
            for (Object[] row : sessionRepository.findLastAnsweredAt(silentWards, CompanionSessionStatus.FINISHED)) {
                lastAnswered.put((String) row[0], (OffsetDateTime) row[1]);
            }
        }
        List<NoDialogueWard> noDialogue = silentWards.stream()
                .map(id -> new NoDialogueWard(id, names.get(id), lastAnswered.get(id)))
                .sorted(Comparator.comparing(NoDialogueWard::lastDialogueAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();

        return new CompanionAdminStatsResponse(date.toString(), prompts.size(), responseRate, silentWards.size(),
                alertSent, distribution, continuing, noDialogue, now.toOffsetDateTime());
    }

    private static MoodDistribution distribution(List<CompanionSession> todaySessions) {
        Map<String, CompanionSession> lastByWard = todaySessions.stream()
                .filter(s -> s.getStatus() == CompanionSessionStatus.FINISHED && s.getEndedAt() != null)
                .collect(Collectors.toMap(CompanionSession::getWardId, Function.identity(),
                        (a, b) -> a.getEndedAt().isAfter(b.getEndedAt()) ? a : b));
        int joy = 0;
        int calm = 0;
        int worry = 0;
        int depressed = 0;
        int unknown = 0;
        for (CompanionSession session : lastByWard.values()) {
            CompanionMood mood = session.getMood();
            if (mood == null) {
                unknown++;
                continue;
            }
            switch (mood) {
                case JOY -> joy++;
                case CALM -> calm++;
                case WORRY -> worry++;
                case DEPRESSED -> depressed++;
            }
        }
        return new MoodDistribution(lastByWard.size(), joy, calm, worry, depressed, unknown);
    }

    private static String key(String wardId, CompanionSlot slot) {
        return wardId + "|" + slot.code();
    }
}

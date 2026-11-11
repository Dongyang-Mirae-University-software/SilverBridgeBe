package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.SlotStatus;
import kr.silverbridge.main.domain.companion.dto.CompanionGuardianDtos.Attention;
import kr.silverbridge.main.domain.companion.dto.CompanionGuardianDtos.Current;
import kr.silverbridge.main.domain.companion.dto.CompanionGuardianDtos.DayMood;
import kr.silverbridge.main.domain.companion.dto.CompanionGuardianDtos.SessionDetailResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionGuardianDtos.Slot;
import kr.silverbridge.main.domain.companion.dto.CompanionGuardianDtos.TodayResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionGuardianDtos.Turn;
import kr.silverbridge.main.domain.companion.dto.CompanionGuardianDtos.WeekResponse;
import kr.silverbridge.main.domain.companion.entity.CompanionMood;
import kr.silverbridge.main.domain.companion.entity.CompanionQuestion;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSessionStatus;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.entity.CompanionTranscriptConsent;
import kr.silverbridge.main.domain.companion.entity.CompanionTurn;
import kr.silverbridge.main.domain.companion.repository.CompanionQuestionRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionTranscriptConsentRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionTurnRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.util.UserIdFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 보호자 안부 대화 화면 (PR-B 2026-11-04, 7일·날짜별·대화 보기 2026-11-11).
 *
 * <p>모든 조회는 ACTIVE 연결 + 지금 연결 이후 대화만(D-1). 날짜별·대화 보기는 최근 {@value #VIEW_DAYS}일(오늘 포함)만 연다
 * (사용자 결정 2026-11-11). 대화 글은 피보호자가 동의했을 때만 저장돼 있고, 동의가 지금 없으면 글을 싣지 않는다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanionGuardianService {

    /** 보호자 화면이 보여 주는 날 수(오늘 포함). */
    public static final int VIEW_DAYS = 7;

    private final CompanionService companionService;
    private final CompanionSessionRepository sessionRepository;
    private final CompanionQuestionRepository questionRepository;
    private final ConnectionService connectionService;
    private final CompanionTurnRepository turnRepository;
    private final CompanionTranscriptConsentRepository consentRepository;
    private final CompanionProperties properties;

    public TodayResponse today(String guardianId, String wardId) {
        OffsetDateTime connectedAt = requireConnectedAt(guardianId, wardId);
        ZonedDateTime now = companionService.now();
        return daySummary(wardId, now.toLocalDate(), now, connectedAt);
    }

    /** 날짜별 - today와 같은 모양. 지난 날은 그날이 끝난 시각 기준(대화 없는 시간대 = MISSED, 다음 시간대 없음). */
    public TodayResponse day(String guardianId, String wardId, String dateText) {
        OffsetDateTime connectedAt = requireConnectedAt(guardianId, wardId);
        ZonedDateTime now = companionService.now();
        LocalDate date = parseViewDate(dateText, now.toLocalDate());
        ZonedDateTime asOf = date.equals(now.toLocalDate()) ? now : date.atTime(LocalTime.MAX).atZone(now.getZone());
        return daySummary(wardId, date, asOf, connectedAt);
    }

    /** 최근 7일(오래된 날부터) 하루 기분 - 하루 기분 규칙은 {@link CompanionMoodDays} 한 곳. */
    public WeekResponse week(String guardianId, String wardId) {
        OffsetDateTime connectedAt = requireConnectedAt(guardianId, wardId);
        LocalDate today = companionService.now().toLocalDate();
        LocalDate from = today.minusDays(VIEW_DAYS - 1);
        Map<LocalDate, CompanionMood> moods = CompanionMoodDays.dailyMoods(CompanionMoodDays.startedSince(
                sessionRepository.findFinishedSince(wardId, CompanionSessionStatus.FINISHED, from), connectedAt));
        List<DayMood> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            CompanionMood mood = moods.get(d);
            days.add(new DayMood(d.toString(), mood == null ? null : mood.name()));
        }
        return new WeekResponse(days);
    }

    /** 대화 보기 - 턴별 기분 흐름 + (동의했으면) 나눈 이야기. */
    public SessionDetailResponse session(String guardianId, String wardId, long sessionId) {
        OffsetDateTime connectedAt = requireConnectedAt(guardianId, wardId);
        CompanionSession session = sessionRepository.findById(sessionId).orElseThrow(
                () -> new CustomException(ErrorCode.COMPANION_SESSION_NOT_FOUND));
        if (!session.getWardId().equals(wardId)) {
            log.warn("[IDOR-ATTEMPT] 다른 피보호자의 안부 대화 조회 시도: guardianId={}, wardId={}, sessionId={}",
                    guardianId, wardId, sessionId);
            throw new CustomException(ErrorCode.COMPANION_SESSION_NOT_FOUND);
        }
        // 연결 전 대화는 없는 것처럼(D-1) - today·week와 같은 기준
        if (session.getStartedAt().isBefore(connectedAt)) {
            throw new CustomException(ErrorCode.COMPANION_SESSION_NOT_FOUND);
        }
        LocalDate today = companionService.now().toLocalDate();
        if (session.getSessionDate().isBefore(today.minusDays(VIEW_DAYS - 1))) {
            throw new CustomException(ErrorCode.COMPANION_DATE_OUT_OF_RANGE);
        }

        List<CompanionTurn> turns = turnRepository.findBySessionIdOrderByTurnNoAsc(sessionId);
        CompanionTranscriptConsent consent = consentRepository.findById(wardId).orElse(null);
        String transcript = transcriptStatus(session, turns, consent);
        boolean showText = "AVAILABLE".equals(transcript);
        List<Turn> turnViews = turns.stream()
                .map(t -> new Turn(t.getTurnNo(), t.getMood() == null ? null : t.getMood().name(),
                        showText ? t.getBotText() : null, showText ? t.getAnswerText() : null))
                .toList();
        int negative = (int) turns.stream().filter(t -> CompanionMoodDays.isConcerning(t.getMood())).count();
        String question = questionRepository.findById(session.getQuestionId())
                .map(CompanionQuestion::getText).orElse(null);
        return new SessionDetailResponse(session.getId(), session.getSessionDate().toString(), session.getSlot().code(),
                question, session.getStatus().name(), session.getMood() == null ? null : session.getMood().name(),
                session.isTest(), session.getTurnCount(), negative, session.getRiskNotifiedAt() != null,
                session.getStartedAt(), session.getEndedAt(), turnViews, transcript);
    }

    /**
     * NO_CONSENT: 지금 동의가 없음(철회 시 글을 비웠다), 또는 동의 전에 시작해 저장된 글이 없음.
     * EXPIRED: 보관 기간이 지나 글을 비웠음. AVAILABLE: 그 밖(동의 뒤 대화 - 대답이 없으면 글이 비어 있을 수 있다).
     */
    private String transcriptStatus(CompanionSession session, List<CompanionTurn> turns,
                                    CompanionTranscriptConsent consent) {
        if (consent == null) {
            return "NO_CONSENT";
        }
        boolean anyText = turns.stream().anyMatch(CompanionTurn::hasText);
        if (anyText) {
            return "AVAILABLE";
        }
        int retention = properties.getTranscriptRetentionDays();
        if (retention > 0 && session.getStartedAt().isBefore(companionService.now().toOffsetDateTime().minusDays(retention))) {
            return "EXPIRED";
        }
        return session.getStartedAt().isBefore(consent.getConsentedAt()) ? "NO_CONSENT" : "AVAILABLE";
    }

    private static LocalDate parseViewDate(String dateText, LocalDate today) {
        LocalDate date;
        try {
            date = LocalDate.parse(dateText == null ? "" : dateText);
        } catch (DateTimeParseException e) {
            throw new CustomException(ErrorCode.COMPANION_DATE_OUT_OF_RANGE);
        }
        if (date.isAfter(today) || date.isBefore(today.minusDays(VIEW_DAYS - 1))) {
            throw new CustomException(ErrorCode.COMPANION_DATE_OUT_OF_RANGE);
        }
        return date;
    }

    private TodayResponse daySummary(String wardId, LocalDate date, ZonedDateTime now, OffsetDateTime connectedAt) {
        // 시간대마다 가장 최근 대화 - 테스트 계정은 같은 시간대에 테스트 대화가 여러 행일 수 있다(2026-11-10, 집계에 포함)
        List<CompanionSession> daySessions = sessionRepository.findByWardIdAndSessionDate(wardId, date)
                .stream().filter(s -> !s.getStartedAt().isBefore(connectedAt)).toList();
        Map<CompanionSlot, CompanionSession> sessions = CompanionService.latestBySlot(daySessions);

        List<Slot> slots = new ArrayList<>();
        for (CompanionSlot slot : CompanionSlot.values()) {
            CompanionSession session = sessions.get(slot);
            SlotStatus status = companionService.slotStatus(slot, session, now);
            slots.add(new Slot(slot.code(), questionText(slot, session, date), status.status(), status.mood(),
                    session == null ? null : session.getId()));
        }

        // 지금 기분·대답한 시간대 수는 그 날 모든 대화로 본다 - 시간대 칸만 최근 대화다(테스트 대화 점검 L-2):
        // 같은 시간대에서 대답한 일반 대화 뒤에 대답 없는 테스트 대화가 와도 대답한 시간대로 센다
        Current current = daySessions.stream()
                .filter(s -> s.getStatus() == CompanionSessionStatus.FINISHED && s.getEndedAt() != null)
                .max(Comparator.comparing(CompanionSession::getEndedAt))
                .map(s -> new Current(s.getSlot().code(), s.getMood() == null ? null : s.getMood().name(),
                        s.getEndedAt(), null))
                .orElse(null);
        int answered = (int) daySessions.stream()
                .filter(s -> s.getAnsweredCount() != null && s.getAnsweredCount() > 0)
                .map(CompanionSession::getSlot)
                .distinct()
                .count();
        String nextSlot = Arrays.stream(CompanionSlot.values())
                .filter(slot -> now.toLocalTime().isBefore(slot.start()))
                .findFirst().map(CompanionSlot::code).orElse(null);

        List<CompanionSession> recent = CompanionMoodDays.startedSince(sessionRepository.findFinishedSince(wardId,
                CompanionSessionStatus.FINISHED, date.minusDays(CompanionMoodDays.LOOKBACK_DAYS)), connectedAt);
        Attention attention = CompanionMoodDays.streak(CompanionMoodDays.dailyMoods(recent), date)
                .filter(CompanionMoodDays.Streak::reachesNotice)
                .map(streak -> new Attention(streak.mood().name(), streak.days()))
                .orElse(null);

        return new TodayResponse(date.toString(), current, answered, CompanionSlot.values().length, nextSlot,
                slots, attention);
    }

    private String questionText(CompanionSlot slot, CompanionSession session, LocalDate date) {
        if (session != null) {
            return questionRepository.findById(session.getQuestionId()).map(CompanionQuestion::getText).orElse(null);
        }
        try {
            return companionService.pickQuestion(slot, date).getText();
        } catch (CustomException e) {
            return null;
        }
    }

    /** ACTIVE 연결이 아니면 403 + 흔적(2026-07-14 정책). 형식이 틀린 ID는 DB를 보지 않고 길이만 남긴다. */
    private OffsetDateTime requireConnectedAt(String guardianId, String wardId) {
        if (!UserIdFormat.isWellFormed(wardId)) {
            log.warn("[IDOR-ATTEMPT] 형식이 맞지 않는 피보호자 ID로 안부 대화 조회 시도: guardianId={}, wardIdLength={}",
                    guardianId, wardId == null ? 0 : wardId.length());
            throw new CustomException(ErrorCode.COMPANION_NOT_AUTHORIZED);
        }
        return connectionService.findActiveConnectedAt(guardianId, wardId).orElseThrow(() -> {
            log.warn("[IDOR-ATTEMPT] 연결되지 않은 피보호자 안부 대화 조회 시도: guardianId={}, wardId={}", guardianId, wardId);
            return new CustomException(ErrorCode.COMPANION_NOT_AUTHORIZED);
        });
    }
}

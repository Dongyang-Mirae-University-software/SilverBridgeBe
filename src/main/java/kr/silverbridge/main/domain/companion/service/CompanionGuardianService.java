package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.dto.CompanionDtos.SlotStatus;
import kr.silverbridge.main.domain.companion.dto.CompanionGuardianDtos.Attention;
import kr.silverbridge.main.domain.companion.dto.CompanionGuardianDtos.Current;
import kr.silverbridge.main.domain.companion.dto.CompanionGuardianDtos.Slot;
import kr.silverbridge.main.domain.companion.dto.CompanionGuardianDtos.TodayResponse;
import kr.silverbridge.main.domain.companion.entity.CompanionQuestion;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSessionStatus;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.repository.CompanionQuestionRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.util.UserIdFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 보호자 "AI 기분 체크" 조회(2026-11-04, PR-B, 노션 카드 "AI 기분 체크 조회 API").
 *
 * <p><b>권한</b>: ACTIVE로 연결된 피보호자만, 그리고 지금 연결이 맺어진 뒤의 대화만 보인다(2026-10-31 D-1, SOS·이상감지와
 * 같은 규칙). 남의 피보호자는 403 {@code COMPANION_NOT_AUTHORIZED} + {@code [IDOR-ATTEMPT]}.</p>
 *
 * <p><b>원문 없음</b>(결정 B): 질문 문장·기분 코드·횟수만 내린다. 한 줄 요약은 칸만 두고 null이다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanionGuardianService {


    private final CompanionService companionService;
    private final CompanionSessionRepository sessionRepository;
    private final CompanionQuestionRepository questionRepository;
    private final ConnectionService connectionService;

    public TodayResponse today(String guardianId, String wardId) {
        OffsetDateTime connectedAt = requireConnectedAt(guardianId, wardId);
        ZonedDateTime now = companionService.now();
        LocalDate date = now.toLocalDate();

        // 시간대마다 가장 최근 대화 - 테스트 계정은 같은 시간대에 테스트 대화가 여러 행일 수 있다(2026-11-10, 집계에 포함)
        List<CompanionSession> todaySessions = sessionRepository.findByWardIdAndSessionDate(wardId, date)
                .stream().filter(s -> !s.getStartedAt().isBefore(connectedAt)).toList();
        Map<CompanionSlot, CompanionSession> sessions = CompanionService.latestBySlot(todaySessions);

        List<Slot> slots = new ArrayList<>();
        for (CompanionSlot slot : CompanionSlot.values()) {
            CompanionSession session = sessions.get(slot);
            SlotStatus status = companionService.slotStatus(slot, session, now);
            slots.add(new Slot(slot.code(), questionText(slot, session, date), status.status(), status.mood()));
        }

        // 지금 기분·대답한 시간대 수는 그 날 모든 대화로 본다 - 시간대 칸만 최근 대화다(테스트 대화 점검 L-2):
        // 같은 시간대에서 대답한 일반 대화 뒤에 대답 없는 테스트 대화가 와도 대답한 시간대로 센다
        Current current = todaySessions.stream()
                .filter(s -> s.getStatus() == CompanionSessionStatus.FINISHED && s.getEndedAt() != null)
                .max(Comparator.comparing(CompanionSession::getEndedAt))
                .map(s -> new Current(s.getSlot().code(), s.getMood() == null ? null : s.getMood().name(),
                        s.getEndedAt(), null))
                .orElse(null);
        int answered = (int) todaySessions.stream()
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

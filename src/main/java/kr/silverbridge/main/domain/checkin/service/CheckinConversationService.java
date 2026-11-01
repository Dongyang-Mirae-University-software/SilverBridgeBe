package kr.silverbridge.main.domain.checkin.service;

import kr.silverbridge.main.domain.checkin.client.CheckinAiClient;
import kr.silverbridge.main.domain.checkin.client.CheckinAiClient.AudioFormat;
import kr.silverbridge.main.domain.checkin.config.CheckinProperties;
import kr.silverbridge.main.domain.checkin.dto.CheckinFinishResponse;
import kr.silverbridge.main.domain.checkin.dto.CheckinSessionResponse;
import kr.silverbridge.main.domain.checkin.dto.CheckinTurnResponse;
import kr.silverbridge.main.domain.checkin.entity.CheckinEmotion;
import kr.silverbridge.main.domain.checkin.entity.CheckinEndReason;
import kr.silverbridge.main.domain.checkin.entity.CheckinResult;
import kr.silverbridge.main.domain.checkin.entity.CheckinSession;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.security.RateLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 피보호자 안부 대화(2026-11-01, 1차). 시작 → 턴(음성 + 선택 사진) → 정리. 사용자 ID는 토큰에서만 받는다(경로·본문으로 받지 않음).
 *
 * <p>규칙: 킬 스위치 → 속도 제한 → 세션 형식(아니면 404, DB 안 봄) → 소유(남의 것 403 + {@code [IDOR-ATTEMPT]}) → 상태.
 * 턴 수·대화 시간은 BE가 센다. 표정 동의가 꺼져 있으면 사진은 읽지도 않고 버린다. 받아쓴 글자·AI 답·요약은 로그에 남기지 않는다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckinConversationService {

    private static final String RATE_START = "checkin-start";
    private static final String RATE_TURN = "checkin-turn";

    private final CheckinProperties properties;
    private final CheckinAiClient aiClient;
    private final CheckinSessionStore store;
    private final CheckinQuestionService questionService;
    private final CheckinSettingService settingService;
    private final CheckinSlots slots;
    private final CheckinIds ids;
    private final CheckinClock clock;
    private final RateLimitService rateLimitService;

    public CheckinSessionResponse start(String wardId) {
        requireActive();
        rateLimitService.check(RATE_START, wardId, properties.getStartsPerMinute(), properties.getStartsPerHour());
        OffsetDateTime now = clock.now();
        LocalDate today = now.toLocalDate();
        CheckinSlot slot = CheckinSlot.current(now.toLocalTime())
                .orElseThrow(() -> new CustomException(ErrorCode.CHECKIN_NOT_OPEN));
        if (store.hasResult(wardId, today, slot)) {
            throw new CustomException(ErrorCode.CHECKIN_ALREADY_DONE);
        }
        boolean windowOpen = now.toLocalDateTime().isBefore(today.atTime(slot.time()).plus(properties.getResponseWindow()));
        Optional<CheckinSession> existing = store.findInProgress(wardId, today, slot);
        if (!windowOpen && existing.filter(s -> s.getTurnCount() > 0).isEmpty()) {
            // 응답 창(결정 Q2: 1시간)이 닫혔다. 나눈 말이 있는 대화는 정리할 수 있게 아래에서 돌려준다.
            throw new CustomException(ErrorCode.CHECKIN_NOT_OPEN);
        }

        if (existing.isPresent()) {
            CheckinSession session = existing.get();
            // 이어서 말할 수 있거나, 나눈 말이 있어 정리만 남았으면 그 대화를 돌려준다(나눈 말을 버리지 않는다).
            if (!finishRequired(session, now) || session.getTurnCount() > 0) {
                log.info("[CHECKIN] 진행 중인 대화 이어받기: wardId={}, sessionId={}", wardId, session.getSessionId());
                return toSessionResponse(session, now, true);
            }
            store.expire(session.getId(), now);
        }

        String question = questionService.questionFor(wardId, slot);
        String sessionId = ids.newSessionId();
        try (CheckinSlots.Slot ignored = slots.acquire(wardId)) {
            aiClient.start(sessionId, wardId, slot.value(), question);
        }
        try {
            CheckinSession created = store.create(CheckinSession.start(sessionId, wardId, today, slot, question, now));
            log.info("[CHECKIN] 대화 시작: wardId={}, sessionId={}, slot={}", wardId, sessionId, slot.value());
            return toSessionResponse(created, now, false);
        } catch (DataIntegrityViolationException e) {
            // 같은 시각을 동시에 시작한 경합 - 먼저 만들어진 대화로 돌린다(AI 쪽 빈 세션은 30일 뒤 자동 삭제).
            CheckinSession winner = store.findInProgress(wardId, today, slot)
                    .orElseThrow(() -> new CustomException(ErrorCode.CHECKIN_BUSY));
            return toSessionResponse(winner, now, true);
        }
    }

    public CheckinTurnResponse turn(String wardId, String sessionId, MultipartFile audio, List<MultipartFile> frames) {
        requireActive();
        rateLimitService.check(RATE_TURN, wardId, properties.getTurnsPerMinute(), properties.getTurnsPerHour());
        CheckinSession session = ownedSession(wardId, sessionId, "turn");
        OffsetDateTime now = clock.now();
        if (!session.isInProgress()) {
            throw new CustomException(ErrorCode.CHECKIN_SESSION_CLOSED);
        }
        if (session.getTurnCount() >= properties.getMaxTurns() || session.isAiDone()
                || now.isAfter(turnDeadline(session))) {
            throw new CustomException(ErrorCode.CHECKIN_FINISH_REQUIRED);
        }

        byte[] audioBytes = readAudio(audio);
        AudioFormat format = AudioFormat.detect(audioBytes);
        if (format == null) {
            throw new CustomException(ErrorCode.CHECKIN_INVALID_AUDIO);
        }
        // 동의가 꺼져 있으면 사진은 읽지도 않는다(AI로 보내지 않음 - 결정 사항).
        List<byte[]> frameBytes = settingService.faceConsent(wardId) ? readFrames(frames) : List.of();

        int turnNo = session.getTurnCount() + 1;
        long startedAt = System.nanoTime();
        CheckinAiProjection.Turn turn;
        try (CheckinSlots.Slot ignored = slots.acquire(wardId)) {
            turn = CheckinAiProjection.turn(
                    aiClient.turn(session.getSessionId(), wardId, turnNo, format, audioBytes, frameBytes));
        }
        if (turn == null) {
            log.warn("[CHECKIN] AI 턴 응답 형식 이상: wardId={}, sessionId={}", wardId, session.getSessionId());
            throw new CustomException(ErrorCode.CHECKIN_UNAVAILABLE);
        }
        CheckinSession updated = store.recordTurn(session.getId(), session.getTurnCount(), turn.done(),
                turn.emergency(), turn.selfHarm());
        log.info("[CHECKIN] 턴 완료: wardId={}, sessionId={}, turnNo={}, frames={}, elapsedMs={}, done={}, risk={}",
                wardId, session.getSessionId(), turnNo, frameBytes.size(), elapsedMs(startedAt), turn.done(),
                turn.emergency() || turn.selfHarm());
        return new CheckinTurnResponse(turnNo, turn.transcript(), turn.reply(),
                Math.max(0, properties.getMaxTurns() - updated.getTurnCount()), finishRequired(updated, clock.now()),
                turn.emergency(), turn.selfHarm());
    }

    public CheckinFinishResponse finish(String wardId, String sessionId, CheckinEmotion selfReport) {
        requireActive();
        rateLimitService.check(RATE_TURN, wardId, properties.getTurnsPerMinute(), properties.getTurnsPerHour());
        CheckinSession session = ownedSession(wardId, sessionId, "finish");
        if (!session.isInProgress()) {
            // 이미 정리됐으면 저장된 결과를 그대로(재전송 멱등). 닫힌 대화는 409.
            return store.findResult(session.getId())
                    .map(CheckinConversationService::toFinishResponse)
                    .orElseThrow(() -> new CustomException(ErrorCode.CHECKIN_SESSION_CLOSED));
        }
        if (selfReport != null && !selfReport.selectableBySelf()) {
            throw new CustomException(ErrorCode.CHECKIN_INVALID_SELF_REPORT);
        }

        OffsetDateTime now = clock.now();
        CheckinEndReason reason = endReasonOf(session, now);
        CheckinAiProjection.Finish finish;
        try (CheckinSlots.Slot ignored = slots.acquire(wardId)) {
            finish = CheckinAiProjection.finish(aiClient.finish(session.getSessionId(), wardId,
                    selfReport == null ? null : selfReport.aiValue(), reason.name()));
        }
        boolean risk = finish.emergency() || session.isRiskEmergency() || finish.selfHarm() || session.isRiskSelfHarm();
        CheckinAttentionJudge.Verdict verdict = CheckinAttentionJudge.judge(new CheckinAttentionJudge.Current(
                        risk, finish.emotion(), selfReport, finish.signal("sleep"), finish.signal("meal"),
                        finish.signal("pain"), finish.signal("mood"), finish.signal("lonely"),
                        finish.avgAnswerSec(), finish.avgCharsPerSec()),
                store.baseline(wardId, session.getCheckDate(), properties.getBaselineDays()));
        CheckinResult result = CheckinResult.builder()
                .sessionPk(session.getId())
                .wardId(wardId)
                .checkDate(session.getCheckDate())
                .slot(session.getSlot())
                .emotion(finish.emotion())
                .selfReport(selfReport)
                .sleep(finish.signal("sleep"))
                .meal(finish.signal("meal"))
                .pain(finish.signal("pain"))
                .mood(finish.signal("mood"))
                .lonely(finish.signal("lonely"))
                .riskEmergency(finish.emergency() || session.isRiskEmergency())
                .riskSelfHarm(finish.selfHarm() || session.isRiskSelfHarm())
                .summary(finish.summary())
                .evidence(finish.evidence())
                .turns(session.getTurnCount())
                .endReason(reason)
                .avgAnswerSec(finish.avgAnswerSec())
                .avgSpeechSec(finish.avgSpeechSec())
                .avgPauseRatio(finish.avgPauseRatio())
                .avgCharsPerSec(finish.avgCharsPerSec())
                .avgLoudnessDb(finish.avgLoudnessDb())
                .createdAt(now)
                .attention(verdict.attention())
                .attentionReasons(verdict.reasons())
                .build();
        CheckinResult saved;
        try {
            saved = store.finish(session.getId(), result, reason, now);
        } catch (DataIntegrityViolationException e) {
            // 같은 시각 결과가 이미 있다(동시 정리 경합) - 저장된 것을 돌려준다.
            saved = store.findResult(session.getId())
                    .orElseThrow(() -> new CustomException(ErrorCode.CHECKIN_ALREADY_DONE));
        }
        log.info("[CHECKIN] 대화 정리: wardId={}, sessionId={}, turns={}, reason={}, emotion={}, risk={}, attention={}",
                wardId, session.getSessionId(), session.getTurnCount(), reason, saved.getEmotion(),
                saved.isRiskEmergency() || saved.isRiskSelfHarm(), saved.isAttention());
        return toFinishResponse(saved);
    }

    // ---------------------------------------------------------------- 내부

    private void requireActive() {
        if (!properties.isActive()) {
            throw new CustomException(ErrorCode.CHECKIN_DISABLED);
        }
    }

    /** 형식이 아니면 404(DB 안 봄), 없으면 404, 남의 대화면 403 + 흔적. 세션 ID 원문은 형식이 맞을 때만 로그에 남긴다. */
    private CheckinSession ownedSession(String wardId, String sessionId, String action) {
        if (!CheckinIds.isSessionIdFormat(sessionId)) {
            throw new CustomException(ErrorCode.CHECKIN_SESSION_NOT_FOUND);
        }
        CheckinSession session = store.find(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHECKIN_SESSION_NOT_FOUND));
        if (!session.getWardId().equals(wardId)) {
            log.warn("[IDOR-ATTEMPT] 남의 안부 대화 접근 시도: wardId={}, sessionId={}, action={}",
                    wardId, sessionId, action);
            throw new CustomException(ErrorCode.CHECKIN_SESSION_NOT_OWNED);
        }
        return session;
    }

    private OffsetDateTime endsAt(CheckinSession session) {
        return session.getStartedAt().plus(properties.getSessionDuration());
    }

    private OffsetDateTime turnDeadline(CheckinSession session) {
        return endsAt(session).plus(properties.getTurnGrace());
    }

    private boolean finishRequired(CheckinSession session, OffsetDateTime now) {
        return session.getTurnCount() >= properties.getMaxTurns() || session.isAiDone() || now.isAfter(endsAt(session));
    }

    private CheckinEndReason endReasonOf(CheckinSession session, OffsetDateTime now) {
        if (session.getTurnCount() >= properties.getMaxTurns()) {
            return CheckinEndReason.TURN_LIMIT;
        }
        if (session.isAiDone()) {
            return CheckinEndReason.AI_END;
        }
        if (now.isAfter(endsAt(session))) {
            return CheckinEndReason.TIME_LIMIT;
        }
        return CheckinEndReason.USER_END;
    }

    /** 크기는 읽기 전에 본다. 비었으면 못 알아들은 것과 같게 안내한다. */
    private byte[] readAudio(MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            throw new CustomException(ErrorCode.CHECKIN_SPEECH_NOT_RECOGNIZED);
        }
        if (audio.getSize() > properties.getMaxAudioBytes()) {
            throw new CustomException(ErrorCode.CHECKIN_AUDIO_TOO_LARGE);
        }
        try {
            return audio.getBytes();
        } catch (IOException e) {
            log.warn("[CHECKIN] 업로드 음성 읽기 실패: error={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.CHECKIN_INVALID_AUDIO);
        }
    }

    /** 동의한 경우에만 부른다. 장수·장당 크기·JPEG 매직 바이트를 확인한다. 빈 파트는 건너뛴다. */
    private List<byte[]> readFrames(List<MultipartFile> frames) {
        if (frames == null || frames.isEmpty()) {
            return List.of();
        }
        List<MultipartFile> present = frames.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (present.size() > properties.getMaxFrames()) {
            throw new CustomException(ErrorCode.CHECKIN_INVALID_FRAME);
        }
        List<byte[]> result = new ArrayList<>(present.size());
        for (MultipartFile frame : present) {
            if (frame.getSize() > properties.getMaxFrameBytes()) {
                throw new CustomException(ErrorCode.CHECKIN_INVALID_FRAME);
            }
            byte[] bytes;
            try {
                bytes = frame.getBytes();
            } catch (IOException e) {
                throw new CustomException(ErrorCode.CHECKIN_INVALID_FRAME);
            }
            if (!CheckinAiClient.isJpeg(bytes)) {
                throw new CustomException(ErrorCode.CHECKIN_INVALID_FRAME);
            }
            result.add(bytes);
        }
        return result;
    }

    private CheckinSessionResponse toSessionResponse(CheckinSession session, OffsetDateTime now, boolean resumed) {
        return new CheckinSessionResponse(session.getSessionId(), session.getSlot(), session.getCheckDate(),
                session.getQuestion(), session.getTurnCount(), properties.getMaxTurns(),
                CheckinClock.atKst(endsAt(session)), resumed, finishRequired(session, now));
    }

    private static CheckinFinishResponse toFinishResponse(CheckinResult result) {
        return new CheckinFinishResponse(result.getSlot(), result.getCheckDate(), result.getEmotion(),
                result.getSelfReport(), result.getEndReason());
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}

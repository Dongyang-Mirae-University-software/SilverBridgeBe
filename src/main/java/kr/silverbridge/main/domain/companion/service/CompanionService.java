package kr.silverbridge.main.domain.companion.service;

import com.fasterxml.jackson.databind.JsonNode;
import kr.silverbridge.main.domain.companion.client.AiCompanionClient;
import kr.silverbridge.main.domain.companion.client.CompanionMedia;
import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.ResultResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.SessionResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.SlotStatus;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.TodayResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.TurnResponse;
import kr.silverbridge.main.domain.companion.entity.CompanionQuestion;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSessionStatus;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.repository.CompanionQuestionRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.security.RateLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 안부 말벗 중계(2026-11-04, PR-A). 피보호자 기기 → 백엔드 → AI.
 *
 * <p><b>원문을 저장하지 않는다</b>(결정 B) - 대답 글·음성·사진·받아 적은 글·말벗 응답 문장은 메모리에서 AI로 넘기고 기기로
 * 돌려줄 뿐 DB·디스크·로그·알림에 남기지 않는다. 저장하는 것은 질문 번호·턴 수·기분 코드·위험 여부다.</p>
 *
 * <p>질문은 백엔드가 고른다(결정 H): 시간대의 활성 질문 중 날짜로 하나({@code epochDay mod 개수}). 대화는 시간대 시작부터
 * {@code windowMinutes}(60분) 안에서만 열고, 시간대는 서버 시각으로 정한다(기기 값을 믿지 않는다).</p>
 *
 * <p>순서: 스위치 → 속도 제한 → 소유 확인 → 상태 → 입력 검사 → 동시 실행 자리 → AI. AI 호출은 트랜잭션 밖이고 쓰기는
 * {@link CompanionSessionWriter}가 짧게 한다. 위험 신호가 처음 나온 턴에 보호자 알림 1번, 마감 때 URGENT인데 아직 안
 * 보냈으면 그때 1번(결정 J).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanionService {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String RATE_LIMIT_ENDPOINT = "companion";
    /** AI가 응답 문장을 주지 않았을 때(계약 위반) 기기가 읽을 고정 문장. */
    private static final String FALLBACK_REPLY = "말씀해 주셔서 고맙습니다.";

    private final AiCompanionClient aiClient;
    private final CompanionProperties properties;
    private final CompanionQuestionRepository questionRepository;
    private final CompanionSessionRepository sessionRepository;
    private final CompanionSessionWriter writer;
    private final CompanionTurnSlots slots;
    private final RateLimitService rateLimitService;

    /** 테스트에서 시각을 고정할 때만 바꾼다. */
    private Clock clock = Clock.system(KST);

    void setClock(Clock clock) {
        this.clock = clock;
    }

    public TodayResponse today(String wardId) {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(KST);
        LocalDate date = now.toLocalDate();
        Map<CompanionSlot, CompanionSession> sessions = sessionRepository.findByWardIdAndSessionDate(wardId, date)
                .stream().collect(Collectors.toMap(CompanionSession::getSlot, Function.identity(), (a, b) -> a));
        CompanionSlot current = currentSlot(now).orElse(null);
        List<SlotStatus> statuses = new ArrayList<>();
        for (CompanionSlot slot : CompanionSlot.values()) {
            statuses.add(slotStatus(slot, sessions.get(slot), now));
        }
        return new TodayResponse(date.toString(), current == null ? null : current.code(), statuses);
    }

    public SessionResponse open(String wardId) {
        requireUsable();
        rateLimitService.check(RATE_LIMIT_ENDPOINT, wardId, properties.getPerMinute(), properties.getPerHour());
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(KST);
        CompanionSlot slot = currentSlot(now).orElseThrow(() -> new CustomException(ErrorCode.COMPANION_NOT_IN_WINDOW));
        LocalDate date = now.toLocalDate();

        var existing = sessionRepository.findByWardIdAndSessionDateAndSlot(wardId, date, slot);
        if (existing.isPresent()) {
            return resumeOrReject(existing.get());
        }
        CompanionQuestion question = pickQuestion(slot, date);
        CompanionSession claimed;
        try {
            claimed = writer.claim(wardId, date, slot, question.getId(), now.toOffsetDateTime());
        } catch (DataIntegrityViolationException e) {
            // 같은 순간 다른 요청이 먼저 선점했다
            return sessionRepository.findByWardIdAndSessionDateAndSlot(wardId, date, slot)
                    .map(this::resumeOrReject)
                    .orElseThrow(() -> new CustomException(ErrorCode.COMPANION_TURN_IN_PROGRESS));
        }

        long sessionId = claimed.getId();
        try (CompanionTurnSlots.Slot ignored = slots.acquire(sessionId)) {
            JsonNode data = aiClient.openDialogue(wardId, sessionId, slot.code(), question.getText(), question.getId());
            String dialogueId = data.path("dialogueId").asText(null);
            if (dialogueId == null || dialogueId.isBlank()) {
                log.warn("[COMPANION] AI 대화 열기 응답에 dialogueId 없음: wardId={}, sessionId={}", wardId, sessionId);
                throw new CustomException(ErrorCode.COMPANION_UNAVAILABLE);
            }
            Integer maxTurns = data.path("maxTurns").isInt() ? data.path("maxTurns").asInt() : null;
            writer.attachDialogue(sessionId, dialogueId, maxTurns, OffsetDateTime.now(clock));
            log.info("[COMPANION] 대화 열림: wardId={}, sessionId={}, slot={}, questionId={}",
                    wardId, sessionId, slot.code(), question.getId());
            return new SessionResponse(sessionId, slot.code(), question.getText(), maxTurns, false);
        } catch (RuntimeException e) {
            writer.releaseClaim(sessionId);
            throw e;
        }
    }

    public TurnResponse replyText(String wardId, long sessionId, String text) {
        requireUsable();
        rateLimitService.check(RATE_LIMIT_ENDPOINT, wardId, properties.getPerMinute(), properties.getPerHour());
        CompanionSession session = requireOpenOwned(wardId, sessionId);
        String answer = text == null ? "" : text.strip();
        if (answer.isEmpty()) {
            throw new CustomException(ErrorCode.COMPANION_ANSWER_EMPTY);
        }
        if (answer.codePointCount(0, answer.length()) > properties.getMaxTextLength()) {
            throw new CustomException(ErrorCode.COMPANION_ANSWER_TOO_LONG);
        }
        try (CompanionTurnSlots.Slot ignored = slots.acquire(sessionId)) {
            // 자리를 잡은 뒤 다시 읽는다 - 앞 요청이 그사이 대화를 끝냈을 수 있다(2026-11-04 점검 M-3)
            CompanionSession current = requireOpenOwned(wardId, sessionId);
            return handleTurn(current, () -> aiClient.replyText(current.getDialogueId(), answer), false);
        }
    }

    public TurnResponse replyAudio(String wardId, long sessionId, MultipartFile audio, List<MultipartFile> frames) {
        requireUsable();
        rateLimitService.check(RATE_LIMIT_ENDPOINT, wardId, properties.getPerMinute(), properties.getPerHour());
        CompanionSession session = requireOpenOwned(wardId, sessionId);
        byte[] audioBytes = readAudio(audio);
        CompanionMedia audioFormat = CompanionMedia.detectAudio(audioBytes);
        if (audioFormat == null) {
            throw new CustomException(ErrorCode.COMPANION_AUDIO_INVALID);
        }
        List<byte[]> frameBytes = new ArrayList<>();
        List<CompanionMedia> frameFormats = new ArrayList<>();
        readFrames(frames, frameBytes, frameFormats);
        try (CompanionTurnSlots.Slot ignored = slots.acquire(sessionId)) {
            CompanionSession current = requireOpenOwned(wardId, sessionId);
            return handleTurn(current, () -> aiClient.replyAudio(current.getDialogueId(), audioBytes, audioFormat,
                    frameBytes, frameFormats), true);
        }
    }

    public ResultResponse finish(String wardId, long sessionId) {
        requireUsable();
        rateLimitService.check(RATE_LIMIT_ENDPOINT, wardId, properties.getPerMinute(), properties.getPerHour());
        CompanionSession session = requireOwned(wardId, sessionId);
        if (session.getStatus() == CompanionSessionStatus.FINISHED) {
            return toResult(session);
        }
        rejectUnfinishable(session);
        try (CompanionTurnSlots.Slot ignored = slots.acquire(sessionId)) {
            // 자리를 잡은 뒤 다시 읽는다 - 마지막 턴이나 마감 스케줄러가 그사이 끝냈으면 저장된 결과를 준다(점검 M-3)
            CompanionSession current = requireOwned(wardId, sessionId);
            if (current.getStatus() == CompanionSessionStatus.FINISHED) {
                return toResult(current);
            }
            rejectUnfinishable(current);
            return toResult(finalizeWithAi(current, false));
        }
    }

    private static void rejectUnfinishable(CompanionSession session) {
        if (session.getStatus() == CompanionSessionStatus.LOST) {
            throw new CustomException(ErrorCode.COMPANION_SESSION_LOST);
        }
        if (!session.isDialogueAttached()) {
            throw new CustomException(ErrorCode.COMPANION_TURN_IN_PROGRESS);
        }
    }

    /** 마감 스케줄러용 - 오래 멈춘 대화를 닫는다. 자리를 못 잡으면(턴 진행 중) 건너뛴다. AI 실패는 예외로 알린다. */
    boolean closeAbandoned(CompanionSession session) {
        CompanionTurnSlots.Slot slot = slots.tryAcquire(session.getId());
        if (slot == null) {
            return false;
        }
        try (slot) {
            // 목록은 자리를 잡기 전에 읽었다 - 그사이 "마치기"·마지막 턴으로 끝났으면 건드리지 않는다(점검 M-3)
            CompanionSession current = sessionRepository.findById(session.getId())
                    .filter(CompanionSession::isOpen).orElse(null);
            if (current == null) {
                return false;
            }
            if (!current.isDialogueAttached()) {
                writer.releaseClaim(current.getId());
                return true;
            }
            finalizeWithAi(current, true);
            return true;
        }
    }

    private TurnResponse handleTurn(CompanionSession session, AiCall call, boolean audio) {
        long sessionId = session.getId();
        JsonNode data;
        try {
            data = call.run();
        } catch (CustomException e) {
            if (e.getErrorCode() == ErrorCode.COMPANION_SESSION_LOST) {
                writer.markLost(sessionId, OffsetDateTime.now(clock));
                log.info("[COMPANION] AI 쪽 대화 없음 - 끊긴 대화로 닫음: sessionId={}", sessionId);
            } else if (e.getErrorCode() == ErrorCode.COMPANION_SESSION_ENDED) {
                // AI는 이미 끝났는데 결과를 못 받았다(마감 응답 유실 등) - 지금 받아 저장한다
                finalizeQuietly(session);
            }
            throw e;
        }

        int turnNo = Math.max(0, data.path("turnNo").asInt(0));
        boolean ended = data.path("ended").asBoolean(false);
        boolean riskSeen = data.path("signals").path("riskFlag").asBoolean(false);
        JsonNode replyNode = data.path("reply").path("text");
        String reply = replyNode.isTextual() && !replyNode.asText().isBlank() ? replyNode.asText() : null;
        if (reply == null) {
            log.warn("[COMPANION] AI 턴 응답에 응답 문장 없음 - 고정 문장 사용: sessionId={}", sessionId);
            reply = FALLBACK_REPLY;
        }
        JsonNode transcriptNode = data.path("transcript");
        String transcript = audio && transcriptNode.isTextual() ? transcriptNode.asText() : null;

        writer.recordTurn(sessionId, turnNo, riskSeen, OffsetDateTime.now(clock));
        String endReason = null;
        if (ended) {
            endReason = finalizeQuietly(session);
        }
        log.info("[COMPANION] 턴 처리: sessionId={}, turnNo={}, ended={}, audio={}", sessionId, turnNo, ended, audio);
        return new TurnResponse(turnNo, reply, ended, endReason, transcript);
    }

    /** 대화가 끝난 턴의 결과 저장. 실패해도 턴 응답은 그대로 내보낸다 - 마감 스케줄러가 다시 받는다. */
    private String finalizeQuietly(CompanionSession session) {
        try {
            CompanionSession finished = finalizeWithAi(session, false);
            return finished.getEndReason() == null ? null : finished.getEndReason().name();
        } catch (CustomException e) {
            log.warn("[COMPANION] 대화 결과 받기 실패 - 마감 스케줄러가 다시 시도: sessionId={}, code={}",
                    session.getId(), e.getErrorCode());
            return null;
        }
    }

    private CompanionSession finalizeWithAi(CompanionSession session, boolean abandoned) {
        long sessionId = session.getId();
        JsonNode data;
        try {
            data = aiClient.finish(session.getDialogueId());
        } catch (CustomException e) {
            if (e.getErrorCode() == ErrorCode.COMPANION_SESSION_LOST) {
                writer.markLost(sessionId, OffsetDateTime.now(clock));
                log.info("[COMPANION] AI 쪽 대화 없음 - 결과 없이 닫음: sessionId={}", sessionId);
            }
            throw e;
        }
        CompanionResult result = CompanionResult.from(data);
        if (result.dialogueId() != null && !result.dialogueId().equals(session.getDialogueId())) {
            log.error("[COMPANION] AI 결과의 대화 번호가 다름 - 저장 안 함: sessionId={}", sessionId);
            throw new CustomException(ErrorCode.COMPANION_UNAVAILABLE);
        }
        if (abandoned) {
            result = result.asAbandoned();
        }
        CompanionSession finished = writer.finish(sessionId, result, OffsetDateTime.now(clock));
        log.info("[COMPANION] 대화 결과 저장: sessionId={}, endReason={}, mood={}, turnCount={}, risk={}",
                sessionId, result.endReason(), result.mood(), result.turnCount(), result.needsRiskAlert());
        return finished;
    }

    private SessionResponse resumeOrReject(CompanionSession session) {
        if (session.getStatus() != CompanionSessionStatus.OPEN) {
            throw new CustomException(ErrorCode.COMPANION_SLOT_DONE);
        }
        if (!session.isDialogueAttached()) {
            throw new CustomException(ErrorCode.COMPANION_TURN_IN_PROGRESS);
        }
        String question = questionRepository.findById(session.getQuestionId())
                .map(CompanionQuestion::getText).orElse(null);
        return new SessionResponse(session.getId(), session.getSlot().code(), question, session.getMaxTurns(), true);
    }

    /** 그 날 그 시간대에 고를 질문(보호자 화면의 아직 열리지 않은 시간대 표시에도 쓴다). */
    CompanionQuestion pickQuestion(CompanionSlot slot, LocalDate date) {
        List<CompanionQuestion> questions = questionRepository.findBySlotAndActiveTrueOrderByIdAsc(slot);
        if (questions.isEmpty()) {
            log.error("[COMPANION] 시간대 활성 질문 없음: slot={}", slot.code());
            throw new CustomException(ErrorCode.COMPANION_UNAVAILABLE);
        }
        return questions.get((int) Math.floorMod(date.toEpochDay(), (long) questions.size()));
    }

    private CompanionSession requireOpenOwned(String wardId, long sessionId) {
        CompanionSession session = requireOwned(wardId, sessionId);
        if (session.getStatus() == CompanionSessionStatus.LOST) {
            throw new CustomException(ErrorCode.COMPANION_SESSION_LOST);
        }
        if (!session.isOpen()) {
            throw new CustomException(ErrorCode.COMPANION_SESSION_ENDED);
        }
        if (!session.isDialogueAttached()) {
            throw new CustomException(ErrorCode.COMPANION_TURN_IN_PROGRESS);
        }
        return session;
    }

    /** 남의 대화는 403으로 막고 흔적을 남긴다(2026-07-14 정책 - 404로 위장하지 않는다). */
    private CompanionSession requireOwned(String wardId, long sessionId) {
        CompanionSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.COMPANION_SESSION_NOT_FOUND));
        if (!session.getWardId().equals(wardId)) {
            log.warn("[IDOR-ATTEMPT] 다른 피보호자의 안부 대화 접근 시도: wardId={}, sessionId={}", wardId, sessionId);
            throw new CustomException(ErrorCode.COMPANION_SESSION_NOT_OWNED);
        }
        return session;
    }

    private void requireUsable() {
        if (!properties.isUsable()) {
            throw new CustomException(ErrorCode.COMPANION_DISABLED);
        }
    }

    /** 크기는 읽기 전에 본다. 비었거나 읽을 수 없으면 음성이 아닌 것으로 본다. */
    private byte[] readAudio(MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            throw new CustomException(ErrorCode.COMPANION_AUDIO_INVALID);
        }
        if (audio.getSize() > properties.getMaxAudioBytes()) {
            throw new CustomException(ErrorCode.COMPANION_AUDIO_TOO_LARGE);
        }
        try {
            return audio.getBytes();
        } catch (IOException e) {
            log.warn("[COMPANION] 업로드 음성 읽기 실패: error={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.COMPANION_AUDIO_INVALID);
        }
    }

    private void readFrames(List<MultipartFile> frames, List<byte[]> bytesOut, List<CompanionMedia> formatsOut) {
        if (frames == null) {
            return;
        }
        List<MultipartFile> present = frames.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (present.size() > properties.getMaxFrames()) {
            throw new CustomException(ErrorCode.COMPANION_FRAME_TOO_LARGE);
        }
        for (MultipartFile frame : present) {
            if (frame.getSize() > properties.getMaxFrameBytes()) {
                throw new CustomException(ErrorCode.COMPANION_FRAME_TOO_LARGE);
            }
            byte[] bytes;
            try {
                bytes = frame.getBytes();
            } catch (IOException e) {
                log.warn("[COMPANION] 업로드 사진 읽기 실패: error={}", e.getClass().getSimpleName());
                throw new CustomException(ErrorCode.COMPANION_FRAME_INVALID);
            }
            CompanionMedia format = CompanionMedia.detectImage(bytes);
            if (format == null) {
                throw new CustomException(ErrorCode.COMPANION_FRAME_INVALID);
            }
            bytesOut.add(bytes);
            formatsOut.add(format);
        }
    }

    /** 지금 시각(KST). 테스트는 {@link #setClock}으로 고정한다. */
    ZonedDateTime now() {
        return ZonedDateTime.now(clock).withZoneSameInstant(KST);
    }

    java.util.Optional<CompanionSlot> currentSlot(ZonedDateTime now) {
        return Arrays.stream(CompanionSlot.values())
                .filter(slot -> slot.contains(now.toLocalTime(), properties.getWindowMinutes()))
                .findFirst();
    }

    SlotStatus slotStatus(CompanionSlot slot, CompanionSession session, ZonedDateTime now) {
        if (session != null) {
            return switch (session.getStatus()) {
                case OPEN -> new SlotStatus(slot.code(), "IN_PROGRESS", null);
                case FINISHED -> new SlotStatus(slot.code(), "DONE",
                        session.getMood() == null ? null : session.getMood().name());
                case LOST -> new SlotStatus(slot.code(), "DONE", null);
            };
        }
        if (now.toLocalTime().isBefore(slot.start())) {
            return new SlotStatus(slot.code(), "UPCOMING", null);
        }
        if (slot.contains(now.toLocalTime(), properties.getWindowMinutes())) {
            return new SlotStatus(slot.code(), "AVAILABLE", null);
        }
        return new SlotStatus(slot.code(), "MISSED", null);
    }

    private static ResultResponse toResult(CompanionSession session) {
        return new ResultResponse(session.getId(), session.getSlot().code(),
                session.getEndReason() == null ? null : session.getEndReason().name(),
                session.getMood() == null ? null : session.getMood().name(),
                session.getTurnCount());
    }

    @FunctionalInterface
    private interface AiCall {
        JsonNode run();
    }
}

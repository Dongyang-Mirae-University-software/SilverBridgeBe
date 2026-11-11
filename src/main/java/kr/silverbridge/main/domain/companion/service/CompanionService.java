package kr.silverbridge.main.domain.companion.service;

import com.fasterxml.jackson.databind.JsonNode;
import kr.silverbridge.main.domain.companion.client.AiCompanionClient;
import kr.silverbridge.main.domain.companion.client.CompanionMedia;
import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.ResultAnalysis;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.ResultResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.SessionResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.SlotStatus;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.TodayResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.TurnAnalysis;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    /** 테스트 안내 푸시 - 자기에게 보내는 것이라도 FCM을 두드리므로 따로 묶는다(2026-11-10). */
    private static final String TEST_PROMPT_RATE_LIMIT_ENDPOINT = "companion-test-prompt";
    private static final int TEST_PROMPT_PER_MINUTE = 3;
    private static final int TEST_PROMPT_PER_HOUR = 30;
    private static final Comparator<CompanionSession> LATEST_FIRST = Comparator
            .comparing(CompanionSession::getStartedAt, Comparator.reverseOrder())
            .thenComparing(CompanionSession::getId, Comparator.nullsLast(Comparator.reverseOrder()));
    /** AI가 응답 문장을 주지 않았을 때(계약 위반) 기기가 읽을 고정 문장. */
    private static final String FALLBACK_REPLY = "말씀해 주셔서 고맙습니다.";

    private final AiCompanionClient aiClient;
    private final CompanionProperties properties;
    private final CompanionQuestionRepository questionRepository;
    private final CompanionSessionRepository sessionRepository;
    private final CompanionSessionWriter writer;
    private final CompanionTurnSlots slots;
    private final RateLimitService rateLimitService;
    private final CompanionPromptScheduler promptScheduler;

    /** 테스트에서 시각을 고정할 때만 바꾼다. */
    private Clock clock = Clock.system(KST);

    void setClock(Clock clock) {
        this.clock = clock;
    }

    public TodayResponse today(String wardId) {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(KST);
        LocalDate date = now.toLocalDate();
        Map<CompanionSlot, CompanionSession> sessions =
                latestBySlot(sessionRepository.findByWardIdAndSessionDate(wardId, date));
        CompanionSlot current = currentSlot(now).orElse(null);
        List<SlotStatus> statuses = new ArrayList<>();
        for (CompanionSlot slot : CompanionSlot.values()) {
            statuses.add(slotStatus(slot, sessions.get(slot), now));
        }
        return new TodayResponse(date.toString(), current == null ? null : current.code(), statuses,
                properties.isTestWard(wardId));
    }

    /**
     * 시간대마다 가장 최근에 시작한 대화(2026-11-10). 일반 대화는 시간대당 1행이지만 테스트 계정은 같은 시간대에 테스트
     * 대화가 여러 행일 수 있다 - 화면은 그중 마지막 대화를 보여 준다(테스트 대화도 집계에서 빼지 않는다).
     */
    static Map<CompanionSlot, CompanionSession> latestBySlot(List<CompanionSession> sessions) {
        return sessions.stream().collect(Collectors.toMap(CompanionSession::getSlot, Function.identity(),
                (a, b) -> LATEST_FIRST.compare(a, b) <= 0 ? a : b));
    }

    public SessionResponse open(String wardId) {
        requireUsable();
        rateLimitService.check(RATE_LIMIT_ENDPOINT, wardId, properties.getPerMinute(), properties.getPerHour());
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(KST);
        CompanionSlot slot = currentSlot(now).orElseThrow(() -> new CustomException(ErrorCode.COMPANION_NOT_IN_WINDOW));
        LocalDate date = now.toLocalDate();

        var existing = sessionRepository.findByWardIdAndSessionDateAndSlotAndTestFalse(wardId, date, slot);
        if (existing.isPresent()) {
            return resumeOrReject(existing.get());
        }
        CompanionQuestion question = pickQuestion(slot, date);
        CompanionSession claimed;
        try {
            claimed = writer.claim(wardId, date, slot, question.getId(), now.toOffsetDateTime());
        } catch (DataIntegrityViolationException e) {
            // 같은 순간 다른 요청이 먼저 선점했다
            return sessionRepository.findByWardIdAndSessionDateAndSlotAndTestFalse(wardId, date, slot)
                    .map(this::resumeOrReject)
                    .orElseThrow(() -> new CustomException(ErrorCode.COMPANION_TURN_IN_PROGRESS));
        }
        return openWithAi(wardId, claimed, slot, question);
    }

    /**
     * 테스트 계정의 시간대 선택 시작(2026-11-10, 최종 QA). 60분 창을 보지 않고 고른 시간대의 오늘 질문으로 연다.
     * 같은 시간대에 진행 중인 테스트 대화가 있으면 그 대화를 이어 가고, 끝났거나 끊겼으면 새 테스트 대화를 연다(몇 번이든).
     * 진행 중 테스트 대화는 시간대당 1개다(V69 부분 UNIQUE) - 동시 요청은 한쪽만 선점하고 다른 쪽은 그 대화를 이어 간다.
     * 테스트 대화도 일반 대화와 똑같이 저장·집계한다({@code is_test}는 표시만).
     */
    public SessionResponse openTest(String wardId, String slotCode) {
        requireUsable();
        requireTestWard(wardId);
        CompanionSlot slot = parseSlot(slotCode);
        rateLimitService.check(RATE_LIMIT_ENDPOINT, wardId, properties.getPerMinute(), properties.getPerHour());
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(KST);
        LocalDate date = now.toLocalDate();

        var open = sessionRepository.findFirstByWardIdAndSessionDateAndSlotAndTestTrueAndStatusOrderByIdDesc(
                wardId, date, slot, CompanionSessionStatus.OPEN);
        if (open.isPresent()) {
            return resumeOrReject(open.get());
        }
        CompanionQuestion question = pickQuestion(slot, date);
        CompanionSession claimed;
        try {
            claimed = writer.claimTest(wardId, date, slot, question.getId(), now.toOffsetDateTime());
        } catch (DataIntegrityViolationException e) {
            // 같은 순간 다른 요청(더블탭)이 먼저 선점했다 - 진행 중 테스트 대화는 시간대당 1개(V69, 점검 M-1)
            return sessionRepository.findFirstByWardIdAndSessionDateAndSlotAndTestTrueAndStatusOrderByIdDesc(
                            wardId, date, slot, CompanionSessionStatus.OPEN)
                    .map(this::resumeOrReject)
                    .orElseThrow(() -> new CustomException(ErrorCode.COMPANION_TURN_IN_PROGRESS));
        }
        log.info("[COMPANION] 테스트 대화 선점: wardId={}, sessionId={}, slot={}", wardId, claimed.getId(), slot.code());
        return openWithAi(wardId, claimed, slot, question);
    }

    /**
     * 테스트 계정이 고른 시간대의 질문 안내 푸시를 자기에게 바로 보낸다(2026-11-10). 정각 안내와 같은 문구·채널이고
     * data에 {@code test=true}만 더한다. 선점 기록({@code companion_prompt_log})은 남기지 않는다 - 응답률은 정각 안내 기준
     * 그대로고, 같은 시간대를 반복해 보낼 수 있다(사용자 결정).
     */
    public void sendTestPrompt(String wardId, String slotCode) {
        requireUsable();
        requireTestWard(wardId);
        CompanionSlot slot = parseSlot(slotCode);
        rateLimitService.check(TEST_PROMPT_RATE_LIMIT_ENDPOINT, wardId, TEST_PROMPT_PER_MINUTE, TEST_PROMPT_PER_HOUR);
        promptScheduler.sendTestPrompt(wardId, slot);
    }

    private void requireTestWard(String wardId) {
        if (!properties.isTestWard(wardId)) {
            throw new CustomException(ErrorCode.COMPANION_TEST_NOT_ALLOWED);
        }
    }

    private static CompanionSlot parseSlot(String slotCode) {
        return CompanionSlot.fromCode(slotCode == null ? null : slotCode.strip())
                .orElseThrow(() -> new CustomException(ErrorCode.COMPANION_SLOT_INVALID));
    }

    private SessionResponse openWithAi(String wardId, CompanionSession claimed, CompanionSlot slot,
                                       CompanionQuestion question) {
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
        // 턴 위험 = 최상위 riskFlag 또는 signals.riskFlag(둘 중 하나라도 true면 위험). 확인(되묻기) 턴은 signals가 null이고
        // 낱말 규칙 결과를 최상위 riskFlag에만 싣는다 - signals만 보면 그 위험이 마감 때로 늦어진다(2026-11-09)
        boolean riskSeen = data.path("riskFlag").asBoolean(false)
                || data.path("signals").path("riskFlag").asBoolean(false);
        JsonNode replyNode = data.path("reply").path("text");
        String reply = replyNode.isTextual() && !replyNode.asText().isBlank() ? replyNode.asText() : null;
        if (reply == null) {
            log.warn("[COMPANION] AI 턴 응답에 응답 문장 없음 - 고정 문장 사용: sessionId={}", sessionId);
            reply = FALLBACK_REPLY;
        }
        JsonNode transcriptNode = data.path("transcript");
        String transcript = audio && transcriptNode.isTextual() ? transcriptNode.asText() : null;

        writer.recordTurn(sessionId, turnNo, riskSeen, riskProbability(data), OffsetDateTime.now(clock));
        String endReason = null;
        if (ended) {
            endReason = finalizeQuietly(session);
        }
        log.info("[COMPANION] 턴 처리: sessionId={}, turnNo={}, ended={}, audio={}", sessionId, turnNo, ended, audio);
        TurnAnalysis analysis = properties.isTestWard(session.getWardId()) ? turnAnalysis(data, riskSeen) : null;
        return new TurnResponse(turnNo, reply, ended, endReason, transcript, analysis);
    }

    /**
     * 턴 위험 확률(AI v9 척도, 2026-11-11). 숫자가 아니거나 0~1 밖이면 null - 여러 날 누적 판정에만 쓰고 로그에 남기지 않는다.
     * 소수 넷째 자리에서 내림한다(기준값을 반올림으로 넘지 않게).
     */
    static BigDecimal riskProbability(JsonNode data) {
        JsonNode node = data.get("riskProbability");
        if (node == null || !node.isNumber()) {
            return null;
        }
        double value = node.asDouble();
        if (!(value >= 0.0 && value <= 1.0)) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.DOWN);
    }

    /**
     * 테스트 계정용 턴 판정 - AI 응답 값을 그대로 옮긴다(만들어 내지 않음). 응답으로만 나가고 DB·로그·알림에 남기지 않는다.
     * 응답에 Jackson 2 타입을 싣지 않으려고 일반 Map으로 바꾼다(2026-10-08 규칙).
     */
    static TurnAnalysis turnAnalysis(JsonNode data, boolean riskSeen) {
        JsonNode turnResult = data.path("turnResult");
        return new TurnAnalysis(textOrNull(turnResult.path("label")), textOrNull(turnResult.path("source")),
                plainObject(data.path("face")), riskSeen, plainObject(data.path("signals")),
                plainObject(data.path("textMood")));
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }

    private static Map<String, Object> plainObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        node.properties().forEach(entry -> out.put(entry.getKey(), plainValue(entry.getValue())));
        return out;
    }

    private static Object plainValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isObject()) {
            return plainObject(node);
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(item -> list.add(plainValue(item)));
            return list;
        }
        return node.asText();
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

    private ResultResponse toResult(CompanionSession session) {
        String mood = session.getMood() == null ? null : session.getMood().name();
        // 테스트 계정에만 대화 판정을 싣는다 - 저장된 결과 값이라 이미 마친 대화를 다시 불러도 같다
        ResultAnalysis analysis = properties.isTestWard(session.getWardId())
                ? new ResultAnalysis(mood, session.getTurnCount(), session.getAnsweredCount(),
                        session.getNoResponseCount(), session.isRiskFlag(), session.getAlertLevel(), session.isTest())
                : null;
        return new ResultResponse(session.getId(), session.getSlot().code(),
                session.getEndReason() == null ? null : session.getEndReason().name(),
                mood, session.getTurnCount(), analysis);
    }

    @FunctionalInterface
    private interface AiCall {
        JsonNode run();
    }
}

package kr.silverbridge.main.domain.companion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.companion.client.AiCompanionClient;
import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.ResultResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.SessionResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.TodayResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.TurnResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.UtteranceAudio;
import kr.silverbridge.main.domain.companion.entity.CompanionEndReason;
import kr.silverbridge.main.domain.companion.entity.CompanionMood;
import kr.silverbridge.main.domain.companion.entity.CompanionQuestion;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.repository.CompanionQuestionRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.security.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 안부 말벗 중계 서비스 - 시간대·질문 선택·소유 확인·턴·위험 신호·마감·끊긴 대화. AI·DB는 목이다. */
class CompanionServiceTest {

    private static final String WARD = "CW0001";
    private static final ZonedDateTime AT_0910 = ZonedDateTime.of(2026, 11, 4, 9, 10, 0, 0, CompanionService.KST);
    private static final LocalDate TODAY = AT_0910.toLocalDate();
    private static final ObjectMapper JSON = new ObjectMapper();

    private AiCompanionClient aiClient;
    private CompanionProperties properties;
    private CompanionQuestionRepository questionRepository;
    private CompanionSessionRepository sessionRepository;
    private CompanionSessionWriter writer;
    private RateLimitService rateLimitService;
    private CompanionService service;

    @BeforeEach
    void setUp() {
        aiClient = mock(AiCompanionClient.class);
        properties = new CompanionProperties();
        properties.setEnabled(true);
        properties.setAiBaseUrl("http://ai.test");
        properties.setApiKey("key");
        questionRepository = mock(CompanionQuestionRepository.class);
        sessionRepository = mock(CompanionSessionRepository.class);
        writer = mock(CompanionSessionWriter.class);
        rateLimitService = mock(RateLimitService.class);
        service = new CompanionService(aiClient, properties, questionRepository, sessionRepository, writer,
                new CompanionTurnSlots(properties), rateLimitService, mock(CompanionPromptScheduler.class));
        service.setClock(Clock.fixed(AT_0910.toInstant(), CompanionService.KST));
    }

    @Test
    @DisplayName("대화 열기: 지금 시간대(09:00)의 질문을 날짜로 고르고 AI 대화 번호를 붙인다")
    void openPicksQuestionByDate() throws Exception {
        List<CompanionQuestion> questions = List.of(question(1L, "질문1"), question(2L, "질문2"), question(3L, "질문3"));
        when(questionRepository.findBySlotAndActiveTrueOrderByIdAsc(CompanionSlot.S09)).thenReturn(questions);
        when(sessionRepository.findByWardIdAndSessionDateAndSlotAndTestFalse(WARD, TODAY, CompanionSlot.S09)).thenReturn(Optional.empty());
        CompanionQuestion expected = questions.get((int) Math.floorMod(TODAY.toEpochDay(), 3L));
        when(writer.claim(eq(WARD), eq(TODAY), eq(CompanionSlot.S09), eq(expected.getId()), any()))
                .thenReturn(sessionWithId(10L, null));
        when(aiClient.openDialogue(WARD, 10L, "09:00", expected.getText(), expected.getId()))
                .thenReturn(json("{\"dialogueId\":\"d-10\",\"maxTurns\":8,\"audio\":{\"index\":0}}"));

        SessionResponse response = service.open(WARD);
        assertThat(response.audio()).isEqualTo(new UtteranceAudio(0));

        assertThat(response.sessionId()).isEqualTo(10L);
        assertThat(response.question()).isEqualTo(expected.getText());
        assertThat(response.maxTurns()).isEqualTo(8);
        assertThat(response.resumed()).isFalse();
        assertThat(response.turnCount()).isZero();
        verify(writer).attachDialogue(eq(10L), eq("d-10"), eq(8), any());
    }

    @Test
    @DisplayName("대화 열기: 진행 중인 대화를 이어 가면 지금까지 기록된 턴 수(turnCount)를 함께 준다 - 기기가 1번째부터 다시 세지 않게")
    void openResumeCarriesTurnCount() {
        CompanionSession open = sessionWithId(11L, "d-11");
        open.recordTurn(3, false, null, AT_0910.toOffsetDateTime());
        when(sessionRepository.findByWardIdAndSessionDateAndSlotAndTestFalse(WARD, TODAY, CompanionSlot.S09)).thenReturn(Optional.of(open));
        when(questionRepository.findById(1L)).thenReturn(Optional.of(question(1L, "이어 갈 질문")));

        SessionResponse response = service.open(WARD);

        assertThat(response.sessionId()).isEqualTo(11L);
        assertThat(response.resumed()).isTrue();
        assertThat(response.turnCount()).isEqualTo(3);
        assertThat(response.audio()).as("이어 가는 대화는 AI를 부르지 않아 목소리가 없다").isNull();
        assertThat(response.maxTurns()).isEqualTo(8);
        verify(aiClient, never()).openDialogue(anyString(), anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("대화 열기: 시간대 밖이면 409, 꺼져 있으면 503이다")
    void openOutsideWindowOrDisabled() {
        service.setClock(Clock.fixed(AT_0910.withHour(10).withMinute(30).toInstant(), CompanionService.KST));
        assertThatThrownBy(() -> service.open(WARD)).extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.COMPANION_NOT_IN_WINDOW);

        properties.setEnabled(false);
        assertThatThrownBy(() -> service.open(WARD)).extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.COMPANION_DISABLED);
    }

    @Test
    @DisplayName("대화 열기: 이 시간대를 이미 마쳤으면 409, AI가 실패하면 선점을 풀어 다시 열 수 있게 한다")
    void openRejectsDoneAndReleasesOnFailure() throws Exception {
        CompanionSession done = sessionWithId(5L, "d-5");
        done.finish(CompanionEndReason.MAX_TURNS, CompanionMood.CALM, 8, 8, 0, false, false, null,
                AT_0910.toOffsetDateTime());
        when(sessionRepository.findByWardIdAndSessionDateAndSlotAndTestFalse(WARD, TODAY, CompanionSlot.S09))
                .thenReturn(Optional.of(done));
        assertThatThrownBy(() -> service.open(WARD)).extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.COMPANION_SLOT_DONE);

        when(sessionRepository.findByWardIdAndSessionDateAndSlotAndTestFalse(WARD, TODAY, CompanionSlot.S09)).thenReturn(Optional.empty());
        when(questionRepository.findBySlotAndActiveTrueOrderByIdAsc(CompanionSlot.S09)).thenReturn(List.of(question(1L, "질문")));
        when(writer.claim(any(), any(), any(), any(), any())).thenReturn(sessionWithId(11L, null));
        when(aiClient.openDialogue(anyString(), anyLong(), anyString(), anyString(), anyLong()))
                .thenThrow(new CustomException(ErrorCode.COMPANION_UNAVAILABLE));

        assertThatThrownBy(() -> service.open(WARD)).isInstanceOf(CustomException.class);
        verify(writer).releaseClaim(11L);
    }

    @Test
    @DisplayName("남의 대화는 403, 빈 대답은 400이다 - AI를 부르지 않는다")
    void ownershipAndEmptyAnswer() {
        when(sessionRepository.findById(20L)).thenReturn(Optional.of(sessionWithId(20L, "d-20", "OTHER1")));
        assertThatThrownBy(() -> service.replyText(WARD, 20L, "안녕하세요"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_SESSION_NOT_OWNED);

        when(sessionRepository.findById(21L)).thenReturn(Optional.of(sessionWithId(21L, "d-21")));
        assertThatThrownBy(() -> service.replyText(WARD, 21L, "   "))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_ANSWER_EMPTY);
        verify(aiClient, never()).replyText(anyString(), anyString());
    }

    @Test
    @DisplayName("턴: 응답 문장을 기기로 돌려주고, 위험 표시·턴 내용(기분·말벗 문장·대답)을 기록에 넘긴다 - 글 저장 여부는 동의로 recorder가 정한다")
    void turnWithRisk() throws Exception {
        when(sessionRepository.findById(30L)).thenReturn(Optional.of(sessionWithId(30L, "d-30")));
        when(aiClient.replyText("d-30", "요즘 너무 힘들어요")).thenReturn(json(
                "{\"turnNo\":2,\"reply\":{\"text\":\"많이 힘드셨군요.\"},\"signals\":{\"riskFlag\":true},\"ended\":false}"));

        TurnResponse response = service.replyText(WARD, 30L, "요즘 너무 힘들어요");

        assertThat(response.reply()).isEqualTo("많이 힘드셨군요.");
        assertThat(response.ended()).isFalse();
        assertThat(response.transcript()).isNull();
        verify(writer).recordTurn(eq(30L), eq(2), eq(true), any(),
                eq(new CompanionTurnRecorder.TurnContent(null, "많이 힘드셨군요.", "요즘 너무 힘들어요")), any());
        verify(aiClient, never()).finish(anyString());
    }

    @Test
    @DisplayName("턴: AI audio({index})를 그대로 넘기고, 없으면 null")
    void turnCarriesAudio() throws Exception {
        when(sessionRepository.findById(33L)).thenReturn(Optional.of(sessionWithId(33L, "d-33")));
        when(aiClient.replyText("d-33", "좋아요")).thenReturn(json(
                "{\"turnNo\":1,\"reply\":{\"text\":\"다행이에요.\"},\"ended\":false,\"audio\":{\"index\":1}}"));
        assertThat(service.replyText(WARD, 33L, "좋아요").audio()).isEqualTo(new UtteranceAudio(1));

        when(aiClient.replyText("d-33", "네")).thenReturn(json(
                "{\"turnNo\":2,\"reply\":{\"text\":\"그렇군요.\"},\"ended\":false,\"audio\":null}"));
        assertThat(service.replyText(WARD, 33L, "네").audio()).isNull();
    }

    @Test
    @DisplayName("턴: AI가 응답 문장을 주지 않아 고정 문장으로 바꾸면 목소리를 싣지 않는다(문장과 소리가 어긋남)")
    void fallbackReplyDropsAudio() throws Exception {
        when(sessionRepository.findById(34L)).thenReturn(Optional.of(sessionWithId(34L, "d-34")));
        when(aiClient.replyText("d-34", "좋아요")).thenReturn(json(
                "{\"turnNo\":1,\"reply\":{\"text\":\"\"},\"ended\":false,\"audio\":{\"index\":1}}"));

        TurnResponse response = service.replyText(WARD, 34L, "좋아요");

        assertThat(response.reply()).isNotBlank();
        assertThat(response.audio()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "확인 턴: signals=null, 최상위 riskFlag=true → 위험"
                    + " | \"replySource\":\"CONFIRM\",\"needsCheck\":true,\"signals\":null,\"riskFlag\":true | true",
            "일반 턴: 두 칸 모두 true → 위험 | \"signals\":{\"riskFlag\":true},\"riskFlag\":true | true",
            "최상위만 false, signals만 true → 위험 | \"signals\":{\"riskFlag\":true},\"riskFlag\":false | true",
            "두 칸 모두 false → 위험 아님 | \"signals\":{\"riskFlag\":false},\"riskFlag\":false | false",
            "확인 턴: signals=null, 최상위 riskFlag=false → 위험 아님"
                    + " | \"replySource\":\"CONFIRM\",\"needsCheck\":true,\"signals\":null,\"riskFlag\":false | false",
            "위험 칸이 하나도 없음 → 위험 아님 | \"replySource\":\"LLM\" | false"
    })
    @DisplayName("턴 위험 = 최상위 riskFlag 또는 signals.riskFlag - 확인(되묻기) 턴의 위험도 그 턴에 기록으로 넘긴다")
    void turnRiskReadsTopLevelOrSignals(String caseName, String riskFields, boolean expectedRisk) throws Exception {
        when(sessionRepository.findById(32L)).thenReturn(Optional.of(sessionWithId(32L, "d-32")));
        when(aiClient.replyText("d-32", "그냥 그래요")).thenReturn(json(
                "{\"turnNo\":3,\"reply\":{\"text\":\"조금 더 말씀해 주실래요?\"}," + riskFields + ",\"ended\":false}"));

        service.replyText(WARD, 32L, "그냥 그래요");

        verify(writer).recordTurn(eq(32L), eq(3), eq(expectedRisk), any(), any(), any());
    }

    @Test
    @DisplayName("턴: 마지막 턴이면 바로 결과를 받아 저장하고 종료 이유 코드를 돌려준다")
    void lastTurnFinalizes() throws Exception {
        CompanionSession session = sessionWithId(31L, "d-31");
        when(sessionRepository.findById(31L)).thenReturn(Optional.of(session));
        when(aiClient.replyText("d-31", "고마워요")).thenReturn(json(
                "{\"turnNo\":8,\"reply\":{\"text\":\"편안한 하루 보내세요.\"},\"signals\":null,\"ended\":true,\"endReason\":\"최대 턴 수\"}"));
        when(aiClient.finish("d-31")).thenReturn(json(
                "{\"dialogueId\":\"d-31\",\"endReason\":\"최대 턴 수\",\"turnCount\":8,\"answeredCount\":7,"
                        + "\"noResponseCount\":1,\"mood\":\"평온\",\"riskFlag\":false,\"alertLevel\":\"INFO\"}"));
        CompanionSession finished = sessionWithId(31L, "d-31");
        finished.finish(CompanionEndReason.MAX_TURNS, CompanionMood.CALM, 8, 7, 1, false, false, null,
                AT_0910.toOffsetDateTime());
        ArgumentCaptor<CompanionResult> captor = ArgumentCaptor.forClass(CompanionResult.class);
        when(writer.finish(eq(31L), captor.capture(), any())).thenReturn(finished);

        TurnResponse response = service.replyText(WARD, 31L, "고마워요");

        assertThat(response.ended()).isTrue();
        assertThat(response.endReason()).isEqualTo("MAX_TURNS");
        assertThat(captor.getValue().mood()).isEqualTo(CompanionMood.CALM);
        assertThat(captor.getValue().answeredCount()).isEqualTo(7);
        assertThat(captor.getValue().needsRiskAlert()).isFalse();
    }

    @Test
    @DisplayName("AI에 대화가 없으면(재시작) 끊긴 대화로 닫고 409를 돌려준다")
    void lostDialogue() {
        when(sessionRepository.findById(40L)).thenReturn(Optional.of(sessionWithId(40L, "d-40")));
        when(aiClient.replyText("d-40", "네")).thenThrow(new CustomException(ErrorCode.COMPANION_SESSION_LOST));

        assertThatThrownBy(() -> service.replyText(WARD, 40L, "네"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_SESSION_LOST);
        verify(writer).markLost(eq(40L), any());
    }

    @Test
    @DisplayName("마감: 이미 마친 대화는 AI를 다시 부르지 않고 저장된 결과를 준다(기분 판단 불가는 null)")
    void finishIdempotent() {
        CompanionSession done = sessionWithId(50L, "d-50");
        done.finish(CompanionEndReason.USER_STOP, null, 3, 2, 1, false, false, null, AT_0910.toOffsetDateTime());
        when(sessionRepository.findById(50L)).thenReturn(Optional.of(done));

        ResultResponse result = service.finish(WARD, 50L);

        assertThat(result.endReason()).isEqualTo("USER_STOP");
        assertThat(result.mood()).isNull();
        verify(aiClient, never()).finish(anyString());
    }

    @Test
    @DisplayName("멈춘 대화 마감은 AI의 USER_STOP을 NO_RESPONSE로 바꿔 저장한다")
    void abandonedIsNoResponse() throws Exception {
        CompanionSession session = sessionWithId(60L, "d-60");
        when(sessionRepository.findById(60L)).thenReturn(Optional.of(session));
        when(aiClient.finish("d-60")).thenReturn(json(
                "{\"dialogueId\":\"d-60\",\"endReason\":\"USER_STOP\",\"turnCount\":2,\"mood\":null,"
                        + "\"riskFlag\":true,\"alertLevel\":\"URGENT\"}"));
        ArgumentCaptor<CompanionResult> captor = ArgumentCaptor.forClass(CompanionResult.class);
        when(writer.finish(eq(60L), captor.capture(), any())).thenReturn(session);

        assertThat(service.closeAbandoned(session)).isTrue();

        assertThat(captor.getValue().endReason()).isEqualTo(CompanionEndReason.NO_RESPONSE);
        assertThat(captor.getValue().mood()).isNull();
        assertThat(captor.getValue().needsRiskAlert()).isTrue();
    }

    @Test
    @DisplayName("멈춘 대화 마감: 목록을 읽은 뒤 그사이 끝난 대화는 건드리지 않는다(점검 M-3)")
    void abandonedSkipsAlreadyFinished() {
        CompanionSession stale = sessionWithId(61L, "d-61");
        CompanionSession nowDone = sessionWithId(61L, "d-61");
        nowDone.finish(CompanionEndReason.USER_STOP, null, 2, 2, 0, false, false, null, AT_0910.toOffsetDateTime());
        when(sessionRepository.findById(61L)).thenReturn(Optional.of(nowDone));

        assertThat(service.closeAbandoned(stale)).isFalse();

        verify(aiClient, never()).finish(anyString());
        verify(writer, never()).finish(anyLong(), any(), any());
    }

    @Test
    @DisplayName("마치기: 자리를 잡은 뒤 다시 읽어 그사이 끝났으면 AI를 부르지 않고 저장된 결과를 준다(점검 M-3)")
    void finishRereadsAfterSlot() {
        CompanionSession open = sessionWithId(62L, "d-62");
        CompanionSession done = sessionWithId(62L, "d-62");
        done.finish(CompanionEndReason.MAX_TURNS, CompanionMood.JOY, 8, 8, 0, false, false, null,
                AT_0910.toOffsetDateTime());
        when(sessionRepository.findById(62L)).thenReturn(Optional.of(open), Optional.of(done));

        ResultResponse result = service.finish(WARD, 62L);

        assertThat(result.endReason()).isEqualTo("MAX_TURNS");
        verify(aiClient, never()).finish(anyString());
    }

    @Test
    @DisplayName("음성 대답: 형식이 아닌 파일은 400, 사진이 5장을 넘으면 413 - AI를 부르지 않는다")
    void audioValidation() {
        when(sessionRepository.findById(70L)).thenReturn(Optional.of(sessionWithId(70L, "d-70")));
        MockMultipartFile notAudio = new MockMultipartFile("audio", "a.wav", "audio/wav", new byte[]{1, 2, 3, 4});
        assertThatThrownBy(() -> service.replyAudio(WARD, 70L, notAudio, null))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_AUDIO_INVALID);

        MockMultipartFile wav = new MockMultipartFile("audio", "a.wav", "audio/wav",
                "RIFF0000WAVEdata".getBytes());
        MockMultipartFile jpg = new MockMultipartFile("frame", "f.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0});
        assertThatThrownBy(() -> service.replyAudio(WARD, 70L, wav, List.of(jpg, jpg, jpg, jpg, jpg, jpg)))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_FRAME_TOO_LARGE);
        verify(aiClient, never()).replyAudio(anyString(), any(), any(), any(), any());
    }

    @Test
    void today_말벗이_꺼져_있으면_열_수_있는_시간대를_주지_않는다() {
        service.setClock(Clock.fixed(AT_0910.withHour(15).withMinute(5).toInstant(), CompanionService.KST));
        when(sessionRepository.findByWardIdAndSessionDate(WARD, TODAY)).thenReturn(List.of());
        properties.setEnabled(false);

        TodayResponse today = service.today(WARD);

        assertThat(today.currentSlot()).isNull();
        assertThat(today.slots()).hasSize(5);
        assertThat(today.testMode()).isFalse();
    }

    @Test
    void today_주소나_키가_비어도_열_수_있는_시간대를_주지_않는다() {
        service.setClock(Clock.fixed(AT_0910.withHour(15).withMinute(5).toInstant(), CompanionService.KST));
        when(sessionRepository.findByWardIdAndSessionDate(WARD, TODAY)).thenReturn(List.of());
        properties.setApiKey("");

        assertThat(service.today(WARD).currentSlot()).isNull();
    }

    @Test
    @DisplayName("오늘 현황: 지난 시간대는 MISSED, 마친 시간대는 기분 코드, 지금 시간대는 AVAILABLE")
    void today() {
        service.setClock(Clock.fixed(AT_0910.withHour(15).withMinute(5).toInstant(), CompanionService.KST));
        CompanionSession morning = sessionWithId(80L, "d-80");
        morning.finish(CompanionEndReason.MAX_TURNS, CompanionMood.JOY, 8, 8, 0, false, false, null,
                AT_0910.toOffsetDateTime());
        when(sessionRepository.findByWardIdAndSessionDate(WARD, TODAY)).thenReturn(List.of(morning));

        TodayResponse today = service.today(WARD);

        assertThat(today.currentSlot()).isEqualTo("15:00");
        assertThat(today.slots()).extracting("slot", "status", "mood").containsExactly(
                org.assertj.core.groups.Tuple.tuple("09:00", "DONE", "JOY"),
                org.assertj.core.groups.Tuple.tuple("12:00", "MISSED", null),
                org.assertj.core.groups.Tuple.tuple("15:00", "AVAILABLE", null),
                org.assertj.core.groups.Tuple.tuple("18:00", "UPCOMING", null),
                org.assertj.core.groups.Tuple.tuple("21:00", "UPCOMING", null));
    }

    private static CompanionQuestion question(long id, String text) {
        CompanionQuestion question = org.springframework.beans.BeanUtils.instantiateClass(CompanionQuestion.class);
        ReflectionTestUtils.setField(question, "id", id);
        ReflectionTestUtils.setField(question, "slot", CompanionSlot.S09);
        ReflectionTestUtils.setField(question, "text", text);
        ReflectionTestUtils.setField(question, "active", true);
        return question;
    }

    private static CompanionSession sessionWithId(long id, String dialogueId) {
        return sessionWithId(id, dialogueId, WARD);
    }

    private static CompanionSession sessionWithId(long id, String dialogueId, String wardId) {
        OffsetDateTime now = AT_0910.toOffsetDateTime();
        CompanionSession session = CompanionSession.claim(wardId, TODAY, CompanionSlot.S09, 1L, now);
        ReflectionTestUtils.setField(session, "id", id);
        if (dialogueId != null) {
            session.attachDialogue(dialogueId, 8, now);
        }
        return session;
    }

    private static JsonNode json(String raw) throws Exception {
        return JSON.readTree(raw);
    }
}

package kr.silverbridge.main.domain.companion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.companion.client.AiCompanionClient;
import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.ResultResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.SessionResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.TurnResponse;
import kr.silverbridge.main.domain.companion.entity.CompanionEndReason;
import kr.silverbridge.main.domain.companion.entity.CompanionMood;
import kr.silverbridge.main.domain.companion.entity.CompanionQuestion;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSessionStatus;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.repository.CompanionQuestionRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.security.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
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

/**
 * 최종 QA 테스트 계정(2026-11-10) - 시간대 선택 시작·테스트 안내 푸시·턴/마치기 판정 결과(analysis).
 * 일반 계정은 두 테스트 API가 403이고 응답에 analysis가 없다.
 */
class CompanionTestModeTest {

    private static final String TESTER = "TW0001";
    private static final String NORMAL = "NW0001";
    /** 15:00 창 밖(10:30)이다 - 테스트 계정은 지금 시각과 상관없이 고른 시간대를 연다. */
    private static final ZonedDateTime AT_1030 = ZonedDateTime.of(2026, 11, 10, 10, 30, 0, 0, CompanionService.KST);
    private static final LocalDate TODAY = AT_1030.toLocalDate();
    private static final ObjectMapper JSON = new ObjectMapper();

    private AiCompanionClient aiClient;
    private CompanionProperties properties;
    private CompanionQuestionRepository questionRepository;
    private CompanionSessionRepository sessionRepository;
    private CompanionSessionWriter writer;
    private RateLimitService rateLimitService;
    private CompanionPromptScheduler promptScheduler;
    private CompanionService service;

    @BeforeEach
    void setUp() {
        aiClient = mock(AiCompanionClient.class);
        properties = new CompanionProperties();
        properties.setEnabled(true);
        properties.setAiBaseUrl("http://ai.test");
        properties.setApiKey("key");
        properties.setTestWardIds(List.of(TESTER));
        questionRepository = mock(CompanionQuestionRepository.class);
        sessionRepository = mock(CompanionSessionRepository.class);
        writer = mock(CompanionSessionWriter.class);
        rateLimitService = mock(RateLimitService.class);
        promptScheduler = mock(CompanionPromptScheduler.class);
        service = new CompanionService(aiClient, properties, questionRepository, sessionRepository, writer,
                new CompanionTurnSlots(properties), rateLimitService, promptScheduler);
        service.setClock(Clock.fixed(AT_1030.toInstant(), CompanionService.KST));
    }

    @Test
    @DisplayName("테스트 시작: 창 밖(10:30)에도 고른 시간대(15:00)의 오늘 질문으로 테스트 대화를 선점해 연다")
    void openTestOutsideWindowPicksSlotQuestion() throws Exception {
        List<CompanionQuestion> questions = List.of(question(31L, "오후 질문1"), question(32L, "오후 질문2"));
        when(questionRepository.findBySlotAndActiveTrueOrderByIdAsc(CompanionSlot.S15)).thenReturn(questions);
        CompanionQuestion expected = questions.get((int) Math.floorMod(TODAY.toEpochDay(), 2L));
        when(writer.claimTest(eq(TESTER), eq(TODAY), eq(CompanionSlot.S15), eq(expected.getId()), any()))
                .thenReturn(session(100L, null, TESTER, CompanionSlot.S15, true));
        when(aiClient.openDialogue(TESTER, 100L, "15:00", expected.getText(), expected.getId()))
                .thenReturn(json("{\"dialogueId\":\"d-100\",\"maxTurns\":8}"));

        SessionResponse response = service.openTest(TESTER, "15:00");

        assertThat(response.slot()).isEqualTo("15:00");
        assertThat(response.question()).isEqualTo(expected.getText());
        assertThat(response.resumed()).isFalse();
        verify(writer).attachDialogue(eq(100L), eq("d-100"), eq(8), any());
        verify(writer, never()).claim(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("테스트 시작: 같은 시간대를 마친 뒤 다시 시작하면 새 테스트 대화를 연다(일반 대화의 '이미 마침'에 막히지 않는다)")
    void openTestRepeatsAfterFinished() throws Exception {
        when(sessionRepository.findFirstByWardIdAndSessionDateAndSlotAndTestTrueAndStatusOrderByIdDesc(
                TESTER, TODAY, CompanionSlot.S15, CompanionSessionStatus.OPEN)).thenReturn(Optional.empty());
        when(questionRepository.findBySlotAndActiveTrueOrderByIdAsc(CompanionSlot.S15))
                .thenReturn(List.of(question(31L, "오후 질문")));
        when(writer.claimTest(eq(TESTER), eq(TODAY), eq(CompanionSlot.S15), eq(31L), any()))
                .thenReturn(session(101L, null, TESTER, CompanionSlot.S15, true),
                        session(102L, null, TESTER, CompanionSlot.S15, true));
        when(aiClient.openDialogue(eq(TESTER), anyLong(), eq("15:00"), eq("오후 질문"), eq(31L)))
                .thenReturn(json("{\"dialogueId\":\"d-a\",\"maxTurns\":8}"), json("{\"dialogueId\":\"d-b\",\"maxTurns\":8}"));

        assertThat(service.openTest(TESTER, "15:00").sessionId()).isEqualTo(101L);
        assertThat(service.openTest(TESTER, "15:00").sessionId()).isEqualTo(102L);
        // 일반 대화 조회(시간대당 1행)는 보지 않는다
        verify(sessionRepository, never()).findByWardIdAndSessionDateAndSlotAndTestFalse(any(), any(), any());
    }

    @Test
    @DisplayName("테스트 시작: 같은 시간대에 진행 중인 테스트 대화가 있으면 그 대화를 이어 간다(resumed=true)")
    void openTestResumesOpenTestSession() {
        CompanionSession open = session(103L, "d-103", TESTER, CompanionSlot.S15, true);
        when(sessionRepository.findFirstByWardIdAndSessionDateAndSlotAndTestTrueAndStatusOrderByIdDesc(
                TESTER, TODAY, CompanionSlot.S15, CompanionSessionStatus.OPEN)).thenReturn(Optional.of(open));
        when(questionRepository.findById(1L)).thenReturn(Optional.of(question(1L, "이어 갈 질문")));

        SessionResponse response = service.openTest(TESTER, "15:00");

        assertThat(response.sessionId()).isEqualTo(103L);
        assertThat(response.resumed()).isTrue();
        verify(writer, never()).claimTest(any(), any(), any(), any(), any());
        verify(aiClient, never()).openDialogue(anyString(), anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("테스트 시작 동시 요청(더블탭): 선점에 진 쪽은 UNIQUE 위반 뒤 이긴 쪽 대화를 이어 가고 AI 대화를 더 열지 않는다(점검 M-1)")
    void openTestLosesClaimRaceResumesWinner() {
        CompanionSession winner = session(104L, "d-104", TESTER, CompanionSlot.S15, true);
        when(sessionRepository.findFirstByWardIdAndSessionDateAndSlotAndTestTrueAndStatusOrderByIdDesc(
                TESTER, TODAY, CompanionSlot.S15, CompanionSessionStatus.OPEN))
                .thenReturn(Optional.empty(), Optional.of(winner));
        CompanionQuestion q = question(31L, "오후 질문");
        when(questionRepository.findBySlotAndActiveTrueOrderByIdAsc(CompanionSlot.S15)).thenReturn(List.of(q));
        when(questionRepository.findById(1L)).thenReturn(Optional.of(q));
        when(writer.claimTest(eq(TESTER), eq(TODAY), eq(CompanionSlot.S15), eq(31L), any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_companion_session_test_open"));

        SessionResponse response = service.openTest(TESTER, "15:00");

        assertThat(response.sessionId()).isEqualTo(104L);
        assertThat(response.resumed()).isTrue();
        verify(aiClient, never()).openDialogue(anyString(), anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("테스트 시작 동시 요청: 이긴 쪽이 아직 AI 대화를 여는 중이면 409 TURN_IN_PROGRESS")
    void openTestLosesClaimRaceWhileWinnerOpening() {
        CompanionSession opening = session(105L, null, TESTER, CompanionSlot.S15, true);
        when(sessionRepository.findFirstByWardIdAndSessionDateAndSlotAndTestTrueAndStatusOrderByIdDesc(
                TESTER, TODAY, CompanionSlot.S15, CompanionSessionStatus.OPEN))
                .thenReturn(Optional.empty(), Optional.of(opening));
        CompanionQuestion q = question(31L, "오후 질문");
        when(questionRepository.findBySlotAndActiveTrueOrderByIdAsc(CompanionSlot.S15)).thenReturn(List.of(q));
        when(writer.claimTest(eq(TESTER), eq(TODAY), eq(CompanionSlot.S15), eq(31L), any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_companion_session_test_open"));

        assertThatThrownBy(() -> service.openTest(TESTER, "15:00"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_TURN_IN_PROGRESS);
    }

    @Test
    @DisplayName("일반 계정: 테스트 시작·테스트 안내는 403 COMPANION_TEST_NOT_ALLOWED이고 아무것도 하지 않는다")
    void normalWardRejected() {
        assertThatThrownBy(() -> service.openTest(NORMAL, "15:00"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_TEST_NOT_ALLOWED);
        assertThatThrownBy(() -> service.sendTestPrompt(NORMAL, "15:00"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_TEST_NOT_ALLOWED);
        verify(writer, never()).claimTest(any(), any(), any(), any(), any());
        verify(promptScheduler, never()).sendTestPrompt(any(), any());
    }

    @Test
    @DisplayName("일반 계정: 기존 60분 창 규칙은 그대로다(10:30 열기 = 409 NOT_IN_WINDOW)")
    void normalWardKeepsWindowRule() {
        assertThatThrownBy(() -> service.open(NORMAL))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_NOT_IN_WINDOW);
        // 테스트 계정도 일반 열기는 창 규칙을 따른다
        assertThatThrownBy(() -> service.open(TESTER))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_NOT_IN_WINDOW);
    }

    @Test
    @DisplayName("말벗이 꺼져 있으면 테스트 계정도 테스트 API가 막힌다(503 DISABLED)")
    void disabledBlocksTestApis() {
        properties.setEnabled(false);
        assertThatThrownBy(() -> service.openTest(TESTER, "15:00"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_DISABLED);
        assertThat(service.today(TESTER).testMode()).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "15:30", "15", "24:00", "abc"})
    @DisplayName("시간대 형식이 틀리면 400 COMPANION_SLOT_INVALID")
    void invalidSlot(String slot) {
        assertThatThrownBy(() -> service.openTest(TESTER, slot))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_SLOT_INVALID);
        assertThatThrownBy(() -> service.sendTestPrompt(TESTER, slot))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPANION_SLOT_INVALID);
    }

    @Test
    @DisplayName("테스트 안내: 분당 3·시간당 30 한도를 따로 걸고 고른 시간대로 스케줄러에 넘긴다")
    void testPromptRateLimitedAndDelegated() {
        service.sendTestPrompt(TESTER, "21:00");

        verify(rateLimitService).check("companion-test-prompt", TESTER, 3, 30);
        verify(promptScheduler).sendTestPrompt(TESTER, CompanionSlot.S21);
    }

    @Test
    @DisplayName("오늘 현황: testMode는 테스트 계정만 true")
    void todayTestMode() {
        assertThat(service.today(TESTER).testMode()).isTrue();
        assertThat(service.today(NORMAL).testMode()).isFalse();
    }

    @Test
    @DisplayName("오늘 현황: 같은 시간대에 대화가 여러 개면 가장 최근에 시작한 대화를 보여 준다")
    void todayShowsLatestPerSlot() {
        CompanionSession older = session(110L, "d-110", TESTER, CompanionSlot.S09, true);
        older.finish(CompanionEndReason.USER_STOP, CompanionMood.JOY, 2, 2, 0, false, false, null,
                AT_1030.toOffsetDateTime());
        CompanionSession newer = session(111L, "d-111", TESTER, CompanionSlot.S09, true);
        ReflectionTestUtils.setField(newer, "startedAt", AT_1030.plusMinutes(1).toOffsetDateTime());
        when(sessionRepository.findByWardIdAndSessionDate(TESTER, TODAY)).thenReturn(List.of(newer, older));

        assertThat(service.today(TESTER).slots().get(0).status()).isEqualTo("IN_PROGRESS");

        when(sessionRepository.findByWardIdAndSessionDate(TESTER, TODAY)).thenReturn(List.of(older, newer));
        assertThat(service.today(TESTER).slots().get(0).status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("턴(테스트 계정): 사진을 장수 그대로 AI에 넘기고 analysis에 AI 판정·표정·신호를 그대로 싣는다")
    void testWardAudioTurnCarriesAnalysis() throws Exception {
        when(sessionRepository.findById(120L)).thenReturn(Optional.of(session(120L, "d-120", TESTER, CompanionSlot.S15, true)));
        when(aiClient.replyAudio(eq("d-120"), any(), any(), any(), any())).thenReturn(json("""
                {"turnNo":1,"turnResult":{"label":"기쁨","source":"TEXT"},
                 "reply":{"text":"좋으셨겠어요."},
                 "face":{"label":"Happiness","confidence":0.82,"frameCount":3,"usable":true,"totalFrames":3,"facesFound":3},
                 "signals":{"emotion":"positive","socialContact":"mentioned","responseEvasion":false,"riskFlag":false},
                 "textMood":{"label":"JOY","turnLabel":"기쁨","confidence":0.91,"usable":true,"reason":null},
                 "riskFlag":false,"ended":false,"transcript":"손주가 왔어요"}
                """));
        MockMultipartFile wav = new MockMultipartFile("audio", "a.wav", "audio/wav", "RIFF0000WAVEdata".getBytes());
        MockMultipartFile jpg = new MockMultipartFile("frame", "f.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0});

        TurnResponse response = service.replyAudio(TESTER, 120L, wav, List.of(jpg, jpg, jpg));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<byte[]>> frames = ArgumentCaptor.forClass(List.class);
        verify(aiClient).replyAudio(eq("d-120"), any(), any(), frames.capture(), any());
        assertThat(frames.getValue()).hasSize(3);

        assertThat(response.analysis()).isNotNull();
        assertThat(response.analysis().turnLabel()).isEqualTo("기쁨");
        assertThat(response.analysis().turnSource()).isEqualTo("TEXT");
        assertThat(response.analysis().face())
                .containsEntry("usable", true).containsEntry("label", "Happiness")
                .containsEntry("totalFrames", 3).containsEntry("facesFound", 3);
        assertThat(response.analysis().signals()).containsEntry("emotion", "positive").containsEntry("riskFlag", false);
        assertThat(response.analysis().textMood())
                .containsEntry("label", "JOY").containsEntry("turnLabel", "기쁨").containsEntry("usable", true)
                .containsEntry("confidence", 0.91).containsKey("reason");
        assertThat(response.analysis().riskFlag()).isFalse();
    }

    @Test
    @DisplayName("턴(테스트 계정): 되묻기 턴은 signals=null, riskFlag는 최상위 값을 따른다")
    void testWardConfirmTurnRisk() throws Exception {
        when(sessionRepository.findById(121L)).thenReturn(Optional.of(session(121L, "d-121", TESTER, CompanionSlot.S15, true)));
        when(aiClient.replyText("d-121", "그냥 그래요")).thenReturn(json(
                "{\"turnNo\":2,\"turnResult\":{\"label\":\"판단 보류\",\"source\":\"TEXT\"},\"reply\":{\"text\":\"다시요?\"},"
                        + "\"signals\":null,\"riskFlag\":true,\"ended\":false}"));

        TurnResponse response = service.replyText(TESTER, 121L, "그냥 그래요");

        assertThat(response.analysis().signals()).isNull();
        assertThat(response.analysis().face()).isNull();
        assertThat(response.analysis().textMood()).as("되묻기 턴·AI가 주지 않으면 null").isNull();
        assertThat(response.analysis().riskFlag()).isTrue();
        verify(writer).recordTurn(eq(121L), eq(2), eq(true), any(), any(), any());
    }

    @Test
    @DisplayName("턴(일반 계정): analysis가 없다")
    void normalWardTurnHasNoAnalysis() throws Exception {
        when(sessionRepository.findById(130L)).thenReturn(Optional.of(session(130L, "d-130", NORMAL, CompanionSlot.S09, false)));
        when(aiClient.replyText("d-130", "좋아요")).thenReturn(json(
                "{\"turnNo\":1,\"turnResult\":{\"label\":\"기쁨\",\"source\":\"TEXT\"},\"reply\":{\"text\":\"네.\"},"
                        + "\"face\":{\"usable\":false},\"signals\":{\"riskFlag\":false},\"riskFlag\":false,\"ended\":false}"));

        assertThat(service.replyText(NORMAL, 130L, "좋아요").analysis()).isNull();
    }

    @Test
    @DisplayName("마치기: 테스트 계정만 analysis(기분·턴 수·위험·alertLevel)를 받는다 - 이미 마친 대화도 같다")
    void finishAnalysisOnlyForTester() {
        CompanionSession tester = session(140L, "d-140", TESTER, CompanionSlot.S15, true);
        tester.finish(CompanionEndReason.USER_STOP, CompanionMood.WORRY, 3, 3, 0, true, true, null,
                AT_1030.toOffsetDateTime());
        CompanionSession normal = session(141L, "d-141", NORMAL, CompanionSlot.S09, false);
        normal.finish(CompanionEndReason.USER_STOP, CompanionMood.CALM, 2, 2, 0, false, false, null,
                AT_1030.toOffsetDateTime());
        when(sessionRepository.findById(140L)).thenReturn(Optional.of(tester));
        when(sessionRepository.findById(141L)).thenReturn(Optional.of(normal));

        ResultResponse result = service.finish(TESTER, 140L);
        assertThat(result.analysis()).isNotNull();
        assertThat(result.analysis().mood()).isEqualTo("WORRY");
        assertThat(result.analysis().turnCount()).isEqualTo(3);
        assertThat(result.analysis().riskFlag()).isTrue();
        assertThat(result.analysis().alertLevel()).isEqualTo("URGENT");
        assertThat(result.analysis().test()).isTrue();

        assertThat(service.finish(NORMAL, 141L).analysis()).isNull();
    }

    @Test
    @DisplayName("응답 직렬화(Jackson 3): 일반 계정 응답에는 analysis 필드 자체가 없고, 테스트 계정은 일반 값으로 나간다")
    void serializationOmitsAnalysisForNormal() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        String normal = mapper.writeValueAsString(new TurnResponse(1, "네.", false, null, null));
        assertThat(normal).doesNotContain("analysis");
        assertThat(mapper.writeValueAsString(new ResultResponse(1L, "09:00", "USER_STOP", null, 2)))
                .doesNotContain("analysis");

        TurnResponse tester = new TurnResponse(1, "네.", false, null, null, CompanionService.turnAnalysis(json(
                "{\"turnResult\":{\"label\":\"평온\",\"source\":\"FACE\"},\"face\":{\"usable\":true,\"confidence\":0.5},"
                        + "\"signals\":{\"emotion\":\"neutral\"}}"), false));
        String body = mapper.writeValueAsString(tester);
        assertThat(body).contains("\"analysis\":{\"turnLabel\":\"평온\",\"turnSource\":\"FACE\"")
                .contains("\"face\":{\"usable\":true,\"confidence\":0.5}")
                .contains("\"signals\":{\"emotion\":\"neutral\"}")
                .doesNotContain("nodeType");
    }

    @Test
    @DisplayName("설정: 테스트 목록에 '*'는 받지 않는다(기동 실패), 빈 칸은 무시한다")
    void testWardIdsRejectWildcard() {
        CompanionProperties p = new CompanionProperties();
        assertThatThrownBy(() -> p.setTestWardIds(List.of("TW0001", "*")))
                .isInstanceOf(IllegalArgumentException.class);
        p.setTestWardIds(List.of(" TW0001 ", ""));
        assertThat(p.getTestWardIds()).containsExactly("TW0001");
        assertThat(p.isTestWard("TW0001")).isFalse(); // 말벗이 꺼져 있으면 테스트 계정도 아니다
    }

    @Test
    @DisplayName("테스트 전체 공개(COMPANION_TEST_ALL_WARDS): 켜면 목록 밖 계정도 테스트 계정, 끄면 목록만, 말벗이 꺼지면 아무도 아님")
    void testAllWardsSwitch() {
        CompanionProperties p = new CompanionProperties();
        p.setEnabled(true);
        p.setAiBaseUrl("http://ai.test");
        p.setApiKey("key");
        p.setTestWardIds(List.of("TW0001"));
        assertThat(p.isTestAllWards()).as("기본 꺼짐").isFalse();
        assertThat(p.isTestWard("OW0001")).isFalse();
        assertThat(p.isTestWard("TW0001")).isTrue();

        p.setTestAllWards(true);
        assertThat(p.isTestWard("OW0001")).isTrue();
        assertThat(p.isTestWard(null)).isFalse();

        p.setEnabled(false);
        assertThat(p.isTestWard("OW0001")).isFalse();
    }

    @Test
    @DisplayName("테스트 전체 공개가 켜지면 목록에 없는 피보호자도 today.testMode=true, 끄면 false")
    void testAllWardsOpensTestApi() {
        properties.setTestAllWards(true);
        assertThat(service.today("OW0001").testMode()).isTrue();
        properties.setTestAllWards(false);
        assertThat(service.today("OW0001").testMode()).isFalse();
    }

    @Test
    @DisplayName("안내 문구: 정각 안내 data는 type·slot뿐이고, 테스트 안내는 같은 문구에 test=true만 더한다")
    void promptContentData() {
        NotificationContent regular = CompanionPromptScheduler.promptContent(CompanionSlot.S15, false);
        NotificationContent test = CompanionPromptScheduler.promptContent(CompanionSlot.S15, true);

        assertThat(regular.data()).isEqualTo(Map.of("type", "COMPANION_PROMPT", "slot", "15:00"));
        assertThat(test.data()).isEqualTo(Map.of("type", "COMPANION_PROMPT", "slot", "15:00", "test", "true"));
        assertThat(test.title()).isEqualTo(regular.title());
        assertThat(test.body()).isEqualTo(regular.body()).isEqualTo("오후 3시 안부 인사 시간이에요. 오늘 이야기를 들려주세요.");
    }

    private static CompanionQuestion question(long id, String text) {
        CompanionQuestion question = org.springframework.beans.BeanUtils.instantiateClass(CompanionQuestion.class);
        ReflectionTestUtils.setField(question, "id", id);
        ReflectionTestUtils.setField(question, "text", text);
        ReflectionTestUtils.setField(question, "active", true);
        return question;
    }

    private static CompanionSession session(long id, String dialogueId, String wardId, CompanionSlot slot, boolean test) {
        OffsetDateTime now = AT_1030.toOffsetDateTime();
        CompanionSession session = test
                ? CompanionSession.claimTest(wardId, TODAY, slot, 1L, now)
                : CompanionSession.claim(wardId, TODAY, slot, 1L, now);
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

package kr.silverbridge.main.domain.checkin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CheckinConversationServiceTest {

    private static final String WARD = "wA1b2C";
    private static final String OTHER = "zZ9y8X";
    private static final String SESSION_ID = "ck_abcdefghijklmnopqrstuvwx";
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final OffsetDateTime AT_1530 = OffsetDateTime.of(2026, 11, 1, 15, 30, 0, 0, KST);
    private static final byte[] WAV = wav();
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01};
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CheckinProperties properties = new CheckinProperties();
    private final CheckinAiClient aiClient = mock(CheckinAiClient.class);
    private final CheckinSessionStore store = mock(CheckinSessionStore.class);
    private final CheckinQuestionService questionService = mock(CheckinQuestionService.class);
    private final CheckinSettingService settingService = mock(CheckinSettingService.class);
    private final CheckinIds ids = mock(CheckinIds.class);
    private final CheckinClock clock = mock(CheckinClock.class);
    private final RateLimitService rateLimitService = mock(RateLimitService.class);
    private CheckinConversationService service;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setAiBaseUrl("http://127.0.0.1:1");
        properties.setApiKey("k");
        service = new CheckinConversationService(properties, aiClient, store, questionService, settingService,
                new CheckinSlots(properties), ids, clock, rateLimitService);
        when(clock.now()).thenReturn(AT_1530);
        when(store.recordTurn(any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenAnswer(inv -> {
                    CheckinSession s = session(WARD, AT_1530.minusSeconds(30), inv.getArgument(1));
                    s.recordTurn(inv.getArgument(2), inv.getArgument(3), inv.getArgument(4));
                    return s;
                });
    }

    @Nested
    @DisplayName("킬 스위치")
    class KillSwitch {

        @Test
        @DisplayName("꺼져 있으면 시작·턴·정리 모두 503 CHECKIN_DISABLED이고 AI·DB를 건드리지 않는다")
        void disabled() {
            properties.setEnabled(false);
            assertCode(() -> service.start(WARD), ErrorCode.CHECKIN_DISABLED);
            assertCode(() -> service.turn(WARD, SESSION_ID, audio(), null), ErrorCode.CHECKIN_DISABLED);
            assertCode(() -> service.finish(WARD, SESSION_ID, null), ErrorCode.CHECKIN_DISABLED);
            verifyNoInteractions(aiClient, store);
        }

        @Test
        @DisplayName("켜져 있어도 AI 주소가 비어 있으면 503 (gosky에 pull돼도 아무 일도 없다)")
        void noBaseUrl() {
            properties.setAiBaseUrl("");
            assertCode(() -> service.start(WARD), ErrorCode.CHECKIN_DISABLED);
            verifyNoInteractions(aiClient, store);
        }

        @Test
        @DisplayName("키가 비어 있어도 503")
        void noKey() {
            properties.setApiKey(" ");
            assertCode(() -> service.start(WARD), ErrorCode.CHECKIN_DISABLED);
        }
    }

    @Nested
    @DisplayName("시작")
    class Start {

        @Test
        @DisplayName("09:00 전에는 409 CHECKIN_NOT_OPEN")
        void beforeNine() {
            when(clock.now()).thenReturn(OffsetDateTime.of(2026, 11, 1, 8, 59, 0, 0, KST));
            assertCode(() -> service.start(WARD), ErrorCode.CHECKIN_NOT_OPEN);
            verifyNoInteractions(aiClient);
        }

        @Test
        @DisplayName("지금 이전의 가장 늦은 시각(15:30 → 15:00)으로, 무작위 세션 ID와 저장된 질문을 AI에 보낸다")
        void createsSession() {
            when(store.findInProgress(WARD, LocalDate.of(2026, 11, 1), CheckinSlot.AT_15)).thenReturn(Optional.empty());
            when(questionService.questionFor(WARD, CheckinSlot.AT_15)).thenReturn("오후엔 뭐 하셨어요?");
            when(ids.newSessionId()).thenReturn(SESSION_ID);
            when(store.create(any())).thenAnswer(inv -> inv.getArgument(0));

            CheckinSessionResponse response = service.start(WARD);

            verify(aiClient).start(SESSION_ID, WARD, "15:00", "오후엔 뭐 하셨어요?");
            assertThat(response.sessionId()).isEqualTo(SESSION_ID).doesNotContain(WARD);
            assertThat(response.slot()).isEqualTo(CheckinSlot.AT_15);
            assertThat(response.resumed()).isFalse();
            assertThat(response.finishRequired()).isFalse();
            assertThat(response.endsAt()).isEqualTo(AT_1530.plusMinutes(2));
        }

        @Test
        @DisplayName("이번 시각 결과가 이미 있으면 409 CHECKIN_ALREADY_DONE")
        void alreadyDone() {
            when(store.hasResult(WARD, LocalDate.of(2026, 11, 1), CheckinSlot.AT_15)).thenReturn(true);
            assertCode(() -> service.start(WARD), ErrorCode.CHECKIN_ALREADY_DONE);
            verifyNoInteractions(aiClient);
        }

        @Test
        @DisplayName("진행 중인 대화가 있으면 새로 만들지 않고 이어받는다")
        void resumes() {
            CheckinSession existing = session(WARD, AT_1530.minusSeconds(40), 2);
            when(store.findInProgress(WARD, LocalDate.of(2026, 11, 1), CheckinSlot.AT_15))
                    .thenReturn(Optional.of(existing));

            CheckinSessionResponse response = service.start(WARD);

            assertThat(response.resumed()).isTrue();
            assertThat(response.turnCount()).isEqualTo(2);
            verify(aiClient, never()).start(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("시간이 지났고 나눈 말이 없는 대화는 닫고 새로 시작한다")
        void expiresEmptyStaleSession() {
            CheckinSession stale = session(WARD, AT_1530.minusMinutes(10), 0);
            when(store.findInProgress(WARD, LocalDate.of(2026, 11, 1), CheckinSlot.AT_15)).thenReturn(Optional.of(stale));
            when(questionService.questionFor(WARD, CheckinSlot.AT_15)).thenReturn("q");
            when(ids.newSessionId()).thenReturn(SESSION_ID);
            when(store.create(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThat(service.start(WARD).resumed()).isFalse();
            verify(store).expire(stale.getId(), AT_1530);
        }

        @Test
        @DisplayName("시간이 지났어도 나눈 말이 있으면 정리만 남은 대화로 돌려준다(말을 버리지 않는다)")
        void staleWithTurnsNeedsFinish() {
            CheckinSession stale = session(WARD, AT_1530.minusMinutes(10), 3);
            when(store.findInProgress(WARD, LocalDate.of(2026, 11, 1), CheckinSlot.AT_15)).thenReturn(Optional.of(stale));

            CheckinSessionResponse response = service.start(WARD);

            assertThat(response.resumed()).isTrue();
            assertThat(response.finishRequired()).isTrue();
            verify(store, never()).expire(any(), any());
        }
    }

    @Nested
    @DisplayName("턴")
    class Turn {

        @Test
        @DisplayName("형식이 아닌 세션 ID는 DB를 보지 않고 404")
        void malformedSessionId() {
            assertCode(() -> service.turn(WARD, "ward_wA1b2C", audio(), null), ErrorCode.CHECKIN_SESSION_NOT_FOUND);
            verifyNoInteractions(store, aiClient);
        }

        @Test
        @DisplayName("남의 세션이면 403 CHECKIN_SESSION_NOT_OWNED이고 AI를 부르지 않는다")
        void othersSession() {
            when(store.find(SESSION_ID)).thenReturn(Optional.of(session(OTHER, AT_1530, 0)));
            assertCode(() -> service.turn(WARD, SESSION_ID, audio(), null), ErrorCode.CHECKIN_SESSION_NOT_OWNED);
            verifyNoInteractions(aiClient);
        }

        @Test
        @DisplayName("표정 동의가 꺼져 있으면 사진을 보내도 AI로 넘기지 않는다")
        void consentOffDropsFrames() {
            when(store.find(SESSION_ID)).thenReturn(Optional.of(session(WARD, AT_1530.minusSeconds(20), 0)));
            when(settingService.faceConsent(WARD)).thenReturn(false);
            when(aiClient.turn(eq(SESSION_ID), eq(WARD), eq(1), eq(AudioFormat.WAV), any(), anyList()))
                    .thenReturn(turnData(false));

            service.turn(WARD, SESSION_ID, audio(), List.of(frame(), frame()));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<byte[]>> frames = ArgumentCaptor.forClass(List.class);
            verify(aiClient).turn(eq(SESSION_ID), eq(WARD), eq(1), eq(AudioFormat.WAV), any(), frames.capture());
            assertThat(frames.getValue()).isEmpty();
        }

        @Test
        @DisplayName("표정 동의가 켜져 있으면 JPEG 사진을 넘긴다")
        void consentOnSendsFrames() {
            when(store.find(SESSION_ID)).thenReturn(Optional.of(session(WARD, AT_1530.minusSeconds(20), 0)));
            when(settingService.faceConsent(WARD)).thenReturn(true);
            when(aiClient.turn(anyString(), anyString(), anyInt(), any(), any(), anyList())).thenReturn(turnData(false));

            service.turn(WARD, SESSION_ID, audio(), List.of(frame(), frame()));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<byte[]>> frames = ArgumentCaptor.forClass(List.class);
            verify(aiClient).turn(anyString(), anyString(), anyInt(), any(), any(), frames.capture());
            assertThat(frames.getValue()).hasSize(2);
        }

        @Test
        @DisplayName("동의가 켜져 있어도 사진이 9장이면 400 CHECKIN_INVALID_FRAME")
        void tooManyFrames() {
            when(store.find(SESSION_ID)).thenReturn(Optional.of(session(WARD, AT_1530.minusSeconds(20), 0)));
            when(settingService.faceConsent(WARD)).thenReturn(true);
            List<org.springframework.web.multipart.MultipartFile> nine =
                    java.util.stream.IntStream.range(0, 9).mapToObj(i -> (org.springframework.web.multipart.MultipartFile) frame()).toList();
            assertCode(() -> service.turn(WARD, SESSION_ID, audio(), nine), ErrorCode.CHECKIN_INVALID_FRAME);
            verifyNoInteractions(aiClient);
        }

        @Test
        @DisplayName("턴 번호는 BE가 센다(지금까지 3턴이면 4번)")
        void turnNumberFromServer() {
            when(store.find(SESSION_ID)).thenReturn(Optional.of(session(WARD, AT_1530.minusSeconds(60), 3)));
            when(aiClient.turn(anyString(), anyString(), anyInt(), any(), any(), anyList())).thenReturn(turnData(false));

            CheckinTurnResponse response = service.turn(WARD, SESSION_ID, audio(), null);

            verify(aiClient).turn(eq(SESSION_ID), eq(WARD), eq(4), eq(AudioFormat.WAV), any(), anyList());
            assertThat(response.turnNo()).isEqualTo(4);
            assertThat(response.remainingTurns()).isEqualTo(4);
        }

        @Test
        @DisplayName("8턴을 채웠으면 409 CHECKIN_FINISH_REQUIRED")
        void turnLimit() {
            when(store.find(SESSION_ID)).thenReturn(Optional.of(session(WARD, AT_1530.minusSeconds(60), 8)));
            assertCode(() -> service.turn(WARD, SESSION_ID, audio(), null), ErrorCode.CHECKIN_FINISH_REQUIRED);
            verifyNoInteractions(aiClient);
        }

        @Test
        @DisplayName("2분 + 여유 60초가 지나면 409 CHECKIN_FINISH_REQUIRED")
        void timeLimit() {
            when(store.find(SESSION_ID)).thenReturn(Optional.of(session(WARD, AT_1530.minusSeconds(181), 1)));
            assertCode(() -> service.turn(WARD, SESSION_ID, audio(), null), ErrorCode.CHECKIN_FINISH_REQUIRED);
        }

        @Test
        @DisplayName("2분은 지났지만 여유 안이면 받고, 정리를 요구한다")
        void graceAccepted() {
            when(store.find(SESSION_ID)).thenReturn(Optional.of(session(WARD, AT_1530.minusSeconds(150), 1)));
            when(store.recordTurn(any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean()))
                    .thenReturn(session(WARD, AT_1530.minusSeconds(150), 2));
            when(aiClient.turn(anyString(), anyString(), anyInt(), any(), any(), anyList())).thenReturn(turnData(false));
            assertThat(service.turn(WARD, SESSION_ID, audio(), null).finishRequired()).isTrue();
        }

        @Test
        @DisplayName("음성이 아닌 파일은 AI에 보내지 않고 400 CHECKIN_INVALID_AUDIO")
        void notAudio() {
            when(store.find(SESSION_ID)).thenReturn(Optional.of(session(WARD, AT_1530, 0)));
            MockMultipartFile text = new MockMultipartFile("audio", "a.wav", "audio/wav", "hello world, not audio".getBytes());
            assertCode(() -> service.turn(WARD, SESSION_ID, text, null), ErrorCode.CHECKIN_INVALID_AUDIO);
            verifyNoInteractions(aiClient);
        }

        @Test
        @DisplayName("5MB를 넘는 음성은 413")
        void audioTooLarge() {
            properties.setMaxAudioBytes(10);
            when(store.find(SESSION_ID)).thenReturn(Optional.of(session(WARD, AT_1530, 0)));
            assertCode(() -> service.turn(WARD, SESSION_ID, audio(), null), ErrorCode.CHECKIN_AUDIO_TOO_LARGE);
        }

        @Test
        @DisplayName("AI가 대화를 마무리하면(done) 정리를 요구하고, 위험 신호를 전달한다")
        void doneAndRisk() throws Exception {
            when(store.find(SESSION_ID)).thenReturn(Optional.of(session(WARD, AT_1530.minusSeconds(20), 0)));
            when(aiClient.turn(anyString(), anyString(), anyInt(), any(), any(), anyList())).thenReturn(MAPPER.readTree(
                    "{\"turnNo\":1,\"transcript\":\"죽고 싶어\",\"reply\":\"109에 전화해 주세요\",\"done\":true,"
                            + "\"risk\":{\"emergency\":false,\"selfHarm\":true}}"));

            CheckinTurnResponse response = service.turn(WARD, SESSION_ID, audio(), null);

            assertThat(response.finishRequired()).isTrue();
            assertThat(response.selfHarm()).isTrue();
            verify(store).recordTurn(any(), eq(0), eq(true), eq(false), eq(true));
        }
    }

    @Nested
    @DisplayName("정리")
    class Finish {

        @Test
        @DisplayName("남의 세션이면 403")
        void othersSession() {
            when(store.find(SESSION_ID)).thenReturn(Optional.of(session(OTHER, AT_1530, 1)));
            assertCode(() -> service.finish(WARD, SESSION_ID, null), ErrorCode.CHECKIN_SESSION_NOT_OWNED);
            verifyNoInteractions(aiClient);
        }

        @Test
        @DisplayName("본인 기분으로 UNDECIDED는 고를 수 없다(400)")
        void undecidedSelfReport() {
            when(store.find(SESSION_ID)).thenReturn(Optional.of(session(WARD, AT_1530, 1)));
            assertCode(() -> service.finish(WARD, SESSION_ID, CheckinEmotion.UNDECIDED),
                    ErrorCode.CHECKIN_INVALID_SELF_REPORT);
        }

        @Test
        @DisplayName("끝난 이유는 서버가 정한다: 8턴이면 TURN_LIMIT, 본인 기분은 AI 한글 값으로 보낸다")
        void reasonTurnLimit() throws Exception {
            CheckinSession s = session(WARD, AT_1530.minusSeconds(100), 8);
            when(store.find(SESSION_ID)).thenReturn(Optional.of(s));
            when(aiClient.finish(anyString(), anyString(), any(), anyString())).thenReturn(finishData());
            when(store.finish(any(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));

            CheckinFinishResponse response = service.finish(WARD, SESSION_ID, CheckinEmotion.CALM);

            verify(aiClient).finish(SESSION_ID, WARD, "편안", "TURN_LIMIT");
            assertThat(response.endReason()).isEqualTo(CheckinEndReason.TURN_LIMIT);
            assertThat(response.emotion()).isEqualTo(CheckinEmotion.LONELY);
        }

        @Test
        @DisplayName("2분이 지났으면 TIME_LIMIT, 그 전이면 USER_END")
        void reasonTime() throws Exception {
            when(aiClient.finish(anyString(), anyString(), any(), anyString())).thenReturn(finishData());
            when(store.finish(any(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));

            when(store.find(SESSION_ID)).thenReturn(Optional.of(session(WARD, AT_1530.minusSeconds(130), 2)));
            service.finish(WARD, SESSION_ID, null);
            verify(aiClient).finish(eq(SESSION_ID), eq(WARD), isNull(), eq("TIME_LIMIT"));

            when(store.find(SESSION_ID)).thenReturn(Optional.of(session(WARD, AT_1530.minusSeconds(30), 2)));
            service.finish(WARD, SESSION_ID, null);
            verify(aiClient).finish(eq(SESSION_ID), eq(WARD), isNull(), eq("USER_END"));
        }

        @Test
        @DisplayName("AI 결과를 허용 목록으로 걸러 저장한다(신호는 영문 코드, 턴 수는 BE 값, 턴 중 위험도 합친다)")
        void savesProjectedResult() throws Exception {
            CheckinSession s = session(WARD, AT_1530.minusSeconds(30), 2);
            s.recordTurn(false, true, false); // 턴 중 응급 신호가 있었다(턴 3)
            when(store.find(SESSION_ID)).thenReturn(Optional.of(s));
            when(aiClient.finish(anyString(), anyString(), any(), anyString())).thenReturn(finishData());
            ArgumentCaptor<CheckinResult> saved = ArgumentCaptor.forClass(CheckinResult.class);
            when(store.finish(any(), saved.capture(), any(), any())).thenAnswer(inv -> inv.getArgument(1));

            service.finish(WARD, SESSION_ID, null);

            CheckinResult r = saved.getValue();
            assertThat(r.getSleep()).isEqualTo("BAD");
            assertThat(r.getMeal()).isEqualTo("ATE");
            assertThat(r.getPain()).isNull(); // "모름"·이상한 값은 null
            assertThat(r.getMood()).isEqualTo("NORMAL");
            assertThat(r.getLonely()).isEqualTo("YES");
            assertThat(r.getTurns()).isEqualTo(3);
            assertThat(r.isRiskEmergency()).isTrue();
            assertThat(r.getEvidence()).extracting(CheckinResult.Evidence::signal).containsExactly("sleep");
            assertThat(r.getAvgLoudnessDb()).isNull(); // 범위 밖 값은 버린다
        }

        @Test
        @DisplayName("이미 정리된 대화면 AI를 다시 부르지 않고 저장된 결과를 돌려준다(멱등)")
        void idempotent() {
            CheckinSession s = session(WARD, AT_1530.minusSeconds(30), 2);
            s.finish(CheckinEndReason.USER_END, false, false, AT_1530);
            when(store.find(SESSION_ID)).thenReturn(Optional.of(s));
            when(store.findResult(s.getId())).thenReturn(Optional.of(CheckinResult.builder()
                    .slot(CheckinSlot.AT_15).checkDate(LocalDate.of(2026, 11, 1)).emotion(CheckinEmotion.JOY)
                    .endReason(CheckinEndReason.USER_END).build()));

            assertThat(service.finish(WARD, SESSION_ID, null).emotion()).isEqualTo(CheckinEmotion.JOY);
            verifyNoInteractions(aiClient);
        }
    }

    // ---------------------------------------------------------------- 도우미

    private static CheckinSession session(String wardId, OffsetDateTime startedAt, int turns) {
        CheckinSession s = CheckinSession.start(SESSION_ID, wardId, LocalDate.of(2026, 11, 1), CheckinSlot.AT_15,
                "q", startedAt);
        ReflectionTestUtils.setField(s, "id", 7L);
        for (int i = 0; i < turns; i++) {
            s.recordTurn(false, false, false);
        }
        return s;
    }

    private static MockMultipartFile audio() {
        return new MockMultipartFile("audio", "rec.wav", "audio/wav", WAV);
    }

    private static MockMultipartFile frame() {
        return new MockMultipartFile("frames", "f.jpg", "image/jpeg", JPEG);
    }

    private static byte[] wav() {
        byte[] b = new byte[44];
        System.arraycopy("RIFF".getBytes(), 0, b, 0, 4);
        System.arraycopy("WAVE".getBytes(), 0, b, 8, 4);
        return b;
    }

    private static JsonNode turnData(boolean done) {
        try {
            return MAPPER.readTree("{\"turnNo\":1,\"transcript\":\"잘 잤어요\",\"reply\":\"다행이에요\",\"done\":" + done
                    + ",\"risk\":{\"emergency\":false,\"selfHarm\":false}}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode finishData() throws Exception {
        return MAPPER.readTree("""
                {"turns":99,"endReason":"USER_END","emotion":"외로움","selfReport":"편안",
                 "signals":{"sleep":"나쁨","meal":"먹음","pain":"모름","mood":"보통","lonely":"있음","extra":"x"},
                 "risk":{"emergency":false,"selfHarm":false},"summary":"잠을 설치셨다고 하셨어요.",
                 "evidence":[{"signal":"sleep","turnNo":1},{"signal":"unknown","turnNo":2},{"signal":"meal","turnNo":99}],
                 "voice":{"avgAnswerSec":1.2,"avgSpeechSec":3.4,"avgPauseRatio":0.2,"avgCharsPerSec":4.0,"avgLoudnessDb":999},
                 "face":null}
                """);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(code);
    }
}

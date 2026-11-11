package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.client.AiCompanionClient;
import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.dto.CompanionGuardianDtos.SessionDetailResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionGuardianDtos.TodayResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionGuardianDtos.WeekResponse;
import kr.silverbridge.main.domain.companion.entity.CompanionMood;
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
import kr.silverbridge.main.global.security.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 보호자 최근 7일·날짜별·대화 보기 (2026-11-11) - 권한(연결·D-1·남의 대화)·7일 범위·동의에 따른 글 표시. */
class CompanionGuardianViewTest {

    private static final String G = "GD0001";
    private static final String W = "CW0001";
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 11, 13, 10, 0, 0, CompanionService.KST);
    private static final LocalDate TODAY = NOW.toLocalDate();

    private CompanionSessionRepository sessionRepository;
    private ConnectionService connectionService;
    private CompanionTurnRepository turnRepository;
    private CompanionTranscriptConsentRepository consentRepository;
    private CompanionGuardianService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(CompanionSessionRepository.class);
        CompanionQuestionRepository questionRepository = mock(CompanionQuestionRepository.class);
        connectionService = mock(ConnectionService.class);
        turnRepository = mock(CompanionTurnRepository.class);
        consentRepository = mock(CompanionTranscriptConsentRepository.class);
        CompanionProperties properties = new CompanionProperties();
        CompanionService companionService = new CompanionService(mock(AiCompanionClient.class), properties,
                questionRepository, sessionRepository, mock(CompanionSessionWriter.class),
                new CompanionTurnSlots(properties), mock(RateLimitService.class), mock(CompanionPromptScheduler.class));
        companionService.setClock(Clock.fixed(NOW.toInstant(), CompanionService.KST));
        service = new CompanionGuardianService(companionService, sessionRepository, questionRepository,
                connectionService, turnRepository, consentRepository, properties);
        when(connectionService.findActiveConnectedAt(G, W)).thenReturn(Optional.of(OffsetDateTime.MIN));
    }

    private static ErrorCode code(Throwable e) {
        return ((CustomException) e).getErrorCode();
    }

    @Test
    @DisplayName("7일: 오래된 날부터 오늘까지 7개, 판정된 대화가 없는 날은 null, 연결 전 대화는 세지 않는다(D-1)")
    void weekMoods() {
        CompanionSession twoDaysAgo = CompanionTestSessions.finished(W, TODAY.minusDays(2), CompanionSlot.S09,
                CompanionMood.WORRY, 3);
        CompanionSession todayJoy = CompanionTestSessions.finished(W, TODAY, CompanionSlot.S09, CompanionMood.JOY, 3);
        when(sessionRepository.findFinishedSince(W, CompanionSessionStatus.FINISHED, TODAY.minusDays(6)))
                .thenReturn(List.of(twoDaysAgo, todayJoy));

        WeekResponse week = service.week(G, W);

        assertThat(week.days()).hasSize(7);
        assertThat(week.days().get(0).date()).isEqualTo(TODAY.minusDays(6).toString());
        assertThat(week.days().get(6).date()).isEqualTo(TODAY.toString());
        assertThat(week.days().get(4).mood()).isEqualTo("WORRY");
        assertThat(week.days().get(6).mood()).isEqualTo("JOY");
        assertThat(week.days().get(5).mood()).isNull();

        // 오늘 대화가 시작된 뒤에 연결한 보호자에게는 그 대화가 보이지 않는다
        when(connectionService.findActiveConnectedAt(G, W))
                .thenReturn(Optional.of(todayJoy.getStartedAt().plusMinutes(1)));
        assertThat(service.week(G, W).days()).allSatisfy(d -> assertThat(d.mood()).isNull());
    }

    @Test
    @DisplayName("연결되지 않은 피보호자: 7일·날짜별·대화 보기 모두 403이고 대화를 읽지 않는다")
    void notConnected() {
        when(connectionService.findActiveConnectedAt(G, W)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.week(G, W)).extracting(CompanionGuardianViewTest::code)
                .isEqualTo(ErrorCode.COMPANION_NOT_AUTHORIZED);
        assertThatThrownBy(() -> service.day(G, W, TODAY.toString())).extracting(CompanionGuardianViewTest::code)
                .isEqualTo(ErrorCode.COMPANION_NOT_AUTHORIZED);
        assertThatThrownBy(() -> service.session(G, W, 1L)).extracting(CompanionGuardianViewTest::code)
                .isEqualTo(ErrorCode.COMPANION_NOT_AUTHORIZED);
        verify(sessionRepository, never()).findById(anyLong());
        verify(turnRepository, never()).findBySessionIdOrderByTurnNoAsc(anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-04", "2026-11-12", "2026/11/10", "어제", ""})
    @DisplayName("날짜별: 7일(오늘 포함) 밖·미래·형식 오류는 400 COMPANION_DATE_OUT_OF_RANGE")
    void dayOutOfRange(String date) {
        assertThatThrownBy(() -> service.day(G, W, date)).extracting(CompanionGuardianViewTest::code)
                .isEqualTo(ErrorCode.COMPANION_DATE_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("날짜별(지난 날): 대화 없는 시간대는 MISSED, 다음 시간대 null, 대화 있는 칸은 sessionId를 싣는다")
    void pastDay() {
        LocalDate day = TODAY.minusDays(6);
        CompanionSession s12 = CompanionTestSessions.finished(W, day, CompanionSlot.S12, CompanionMood.CALM, 2);
        when(sessionRepository.findByWardIdAndSessionDate(W, day)).thenReturn(List.of(s12));
        when(sessionRepository.findFinishedSince(eq(W), eq(CompanionSessionStatus.FINISHED), any()))
                .thenReturn(List.of(s12));

        TodayResponse response = service.day(G, W, day.toString());

        assertThat(response.date()).isEqualTo(day.toString());
        assertThat(response.nextSlot()).isNull();
        assertThat(response.slots()).extracting(s -> s.status())
                .containsExactly("MISSED", "DONE", "MISSED", "MISSED", "MISSED");
        assertThat(response.slots().get(1).sessionId()).isEqualTo(s12.getId());
        assertThat(response.slots().get(0).sessionId()).isNull();
        assertThat(response.current().mood()).isEqualTo("CALM");
    }

    @Test
    @DisplayName("today: 응답 모양은 그대로이고 slots에 sessionId만 더해진다")
    void todayHasSessionId() {
        CompanionSession s09 = CompanionTestSessions.finished(W, TODAY, CompanionSlot.S09, CompanionMood.JOY, 2);
        when(sessionRepository.findByWardIdAndSessionDate(W, TODAY)).thenReturn(List.of(s09));

        TodayResponse today = service.today(G, W);

        assertThat(today.slots().get(0).sessionId()).isEqualTo(s09.getId());
        assertThat(today.nextSlot()).isEqualTo("15:00");
    }

    @Test
    @DisplayName("대화 보기: 다른 피보호자의 대화·연결 전 대화·없는 대화는 404, 턴을 읽지 않는다")
    void sessionNotVisible() {
        CompanionSession other = CompanionTestSessions.finished("OW0001", TODAY, CompanionSlot.S09, null, 1);
        when(sessionRepository.findById(other.getId())).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.session(G, W, other.getId())).extracting(CompanionGuardianViewTest::code)
                .isEqualTo(ErrorCode.COMPANION_SESSION_NOT_FOUND);

        CompanionSession before = CompanionTestSessions.finished(W, TODAY, CompanionSlot.S09, null, 1);
        when(sessionRepository.findById(before.getId())).thenReturn(Optional.of(before));
        when(connectionService.findActiveConnectedAt(G, W)).thenReturn(Optional.of(before.getStartedAt().plusSeconds(1)));
        assertThatThrownBy(() -> service.session(G, W, before.getId())).extracting(CompanionGuardianViewTest::code)
                .isEqualTo(ErrorCode.COMPANION_SESSION_NOT_FOUND);

        assertThatThrownBy(() -> service.session(G, W, 999_999L)).extracting(CompanionGuardianViewTest::code)
                .isEqualTo(ErrorCode.COMPANION_SESSION_NOT_FOUND);
        verify(turnRepository, never()).findBySessionIdOrderByTurnNoAsc(anyLong());
    }

    @Test
    @DisplayName("대화 보기: 7일보다 오래된 대화는 400")
    void sessionOlderThanWeek() {
        CompanionSession old = CompanionTestSessions.finished(W, TODAY.minusDays(7), CompanionSlot.S09, null, 1);
        when(sessionRepository.findById(old.getId())).thenReturn(Optional.of(old));

        assertThatThrownBy(() -> service.session(G, W, old.getId())).extracting(CompanionGuardianViewTest::code)
                .isEqualTo(ErrorCode.COMPANION_DATE_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("대화 보기(동의함): 턴별 기분·글·걱정 턴 수, transcript=AVAILABLE")
    void sessionWithConsent() {
        CompanionSession session = CompanionTestSessions.finished(W, TODAY, CompanionSlot.S09, CompanionMood.WORRY, 2);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(turnRepository.findBySessionIdOrderByTurnNoAsc(session.getId())).thenReturn(List.of(
                CompanionTurn.of(session.getId(), 1, CompanionMood.CALM, "다행이에요.", "잘 잤어요", session.getStartedAt()),
                CompanionTurn.of(session.getId(), 2, CompanionMood.WORRY, "걱정되시겠어요.", "무릎이 아파요",
                        session.getStartedAt())));
        when(consentRepository.findById(W)).thenReturn(Optional.of(
                CompanionTranscriptConsent.of(W, session.getStartedAt().minusDays(1))));

        SessionDetailResponse detail = service.session(G, W, session.getId());

        assertThat(detail.transcript()).isEqualTo("AVAILABLE");
        assertThat(detail.mood()).isEqualTo("WORRY");
        assertThat(detail.slot()).isEqualTo("09:00");
        assertThat(detail.negativeCount()).isEqualTo(1);
        assertThat(detail.riskAlerted()).isFalse();
        assertThat(detail.turns()).extracting(t -> t.mood()).containsExactly("CALM", "WORRY");
        assertThat(detail.turns().get(1).answerText()).isEqualTo("무릎이 아파요");
        assertThat(detail.turns().get(1).botText()).isEqualTo("걱정되시겠어요.");
    }

    @Test
    @DisplayName("대화 보기(동의 없음): 기분만, 글은 null(혹시 남아 있어도 싣지 않음), transcript=NO_CONSENT")
    void sessionWithoutConsent() {
        CompanionSession session = CompanionTestSessions.finished(W, TODAY, CompanionSlot.S09, CompanionMood.JOY, 1);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(turnRepository.findBySessionIdOrderByTurnNoAsc(session.getId())).thenReturn(List.of(
                CompanionTurn.of(session.getId(), 1, CompanionMood.JOY, "좋네요.", "남은 글", session.getStartedAt())));
        when(consentRepository.findById(W)).thenReturn(Optional.empty());

        SessionDetailResponse detail = service.session(G, W, session.getId());

        assertThat(detail.transcript()).isEqualTo("NO_CONSENT");
        assertThat(detail.turns()).singleElement().satisfies(t -> {
            assertThat(t.mood()).isEqualTo("JOY");
            assertThat(t.botText()).isNull();
            assertThat(t.answerText()).isNull();
        });
    }

    @Test
    @DisplayName("대화 보기: 동의 전에 시작해 글이 없는 대화는 NO_CONSENT")
    void sessionBeforeConsent() {
        CompanionSession session = CompanionTestSessions.finished(W, TODAY, CompanionSlot.S09, CompanionMood.JOY, 1);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(turnRepository.findBySessionIdOrderByTurnNoAsc(session.getId())).thenReturn(List.of(
                CompanionTurn.of(session.getId(), 1, CompanionMood.JOY, null, null, session.getStartedAt())));
        when(consentRepository.findById(W)).thenReturn(Optional.of(
                CompanionTranscriptConsent.of(W, session.getStartedAt().plusHours(1))));

        assertThat(service.session(G, W, session.getId()).transcript()).isEqualTo("NO_CONSENT");
    }
}

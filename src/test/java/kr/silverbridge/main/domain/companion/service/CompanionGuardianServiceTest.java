package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.client.AiCompanionClient;
import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.dto.CompanionGuardianDtos.TodayResponse;
import kr.silverbridge.main.domain.companion.entity.CompanionMood;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSessionStatus;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.repository.CompanionQuestionRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.security.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static kr.silverbridge.main.domain.companion.service.CompanionTestSessions.finished;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 보호자 "AI 기분 체크" - 권한(연결·연결 이후 기록), 지금 기분, 확인할 일. 원문 칸은 계약에 없다. */
class CompanionGuardianServiceTest {

    private static final String G = "GD0001";
    private static final String W = "CW0001";
    private static final ZonedDateTime AT_1310 = ZonedDateTime.of(2026, 11, 4, 13, 10, 0, 0, CompanionService.KST);
    private static final LocalDate TODAY = AT_1310.toLocalDate();

    private CompanionSessionRepository sessionRepository;
    private ConnectionService connectionService;
    private CompanionGuardianService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(CompanionSessionRepository.class);
        CompanionQuestionRepository questionRepository = mock(CompanionQuestionRepository.class);
        connectionService = mock(ConnectionService.class);
        CompanionProperties properties = new CompanionProperties();
        CompanionService companionService = new CompanionService(mock(AiCompanionClient.class), properties,
                questionRepository, sessionRepository, mock(CompanionSessionWriter.class),
                new CompanionTurnSlots(properties), mock(RateLimitService.class), mock(CompanionPromptScheduler.class));
        companionService.setClock(Clock.fixed(AT_1310.toInstant(), CompanionService.KST));
        service = new CompanionGuardianService(companionService, sessionRepository, questionRepository,
                connectionService);
    }

    @Test
    @DisplayName("연결되지 않은 피보호자·형식이 틀린 ID는 403이고 대화를 읽지 않는다")
    void notConnected() {
        when(connectionService.findActiveConnectedAt(G, W)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.today(G, W)).extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.COMPANION_NOT_AUTHORIZED);

        assertThatThrownBy(() -> service.today(G, "../x")).extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.COMPANION_NOT_AUTHORIZED);
        verify(connectionService, never()).findActiveConnectedAt(eq(G), eq("../x"));
        verify(sessionRepository, never()).findByWardIdAndSessionDate(anyString(), any());
    }

    @Test
    @DisplayName("같은 시간대 여러 대화(테스트 계정): 칸은 최근 시작 대화, 지금 기분은 가장 늦게 마친 대화, "
            + "대답한 시간대는 그 시간대 어느 대화든 대답이 있으면 1로 센다(점검 L-2)")
    void multipleSessionsInSlot() {
        when(connectionService.findActiveConnectedAt(G, W)).thenReturn(Optional.of(OffsetDateTime.MIN));
        // 일반 대화: 09:01 시작·대답 4번·09:40 마침 / 테스트 대화: 09:20 시작·대답 없음·09:25 마침
        CompanionSession normal = CompanionSession.claim(W, TODAY, CompanionSlot.S09, 1L, CompanionTestSessions.at(TODAY, CompanionSlot.S09, 1));
        org.springframework.test.util.ReflectionTestUtils.setField(normal, "id", 9001L);
        normal.attachDialogue("d-9001", 8, CompanionTestSessions.at(TODAY, CompanionSlot.S09, 1));
        normal.finish(kr.silverbridge.main.domain.companion.entity.CompanionEndReason.USER_STOP, CompanionMood.JOY,
                4, 4, 0, false, false, null, CompanionTestSessions.at(TODAY, CompanionSlot.S09, 40));
        CompanionSession test = CompanionSession.claimTest(W, TODAY, CompanionSlot.S09, 1L,
                CompanionTestSessions.at(TODAY, CompanionSlot.S09, 20));
        org.springframework.test.util.ReflectionTestUtils.setField(test, "id", 9002L);
        test.attachDialogue("d-9002", 8, CompanionTestSessions.at(TODAY, CompanionSlot.S09, 20));
        test.finish(kr.silverbridge.main.domain.companion.entity.CompanionEndReason.USER_STOP, null,
                1, 0, 1, false, false, null, CompanionTestSessions.at(TODAY, CompanionSlot.S09, 25));
        when(sessionRepository.findByWardIdAndSessionDate(W, TODAY)).thenReturn(List.of(normal, test));
        when(sessionRepository.findFinishedSince(eq(W), eq(CompanionSessionStatus.FINISHED), any()))
                .thenReturn(List.of());

        TodayResponse today = service.today(G, W);

        assertThat(today.slots().get(0).mood()).as("칸은 최근 시작한 테스트 대화(판단 불가)").isNull();
        assertThat(today.current().mood()).as("지금 기분은 가장 늦게 마친 일반 대화").isEqualTo("JOY");
        assertThat(today.answeredCount()).as("09:00에 대답한 대화가 있다").isEqualTo(1);
    }

    @Test
    @DisplayName("지금 기분은 오늘 가장 최근에 마친 대화, 연결 전에 시작한 대화는 보이지 않는다")
    void currentMoodAndVisibility() {
        when(connectionService.findActiveConnectedAt(G, W))
                .thenReturn(Optional.of(CompanionTestSessions.at(TODAY, CompanionSlot.S12, 0)));
        when(sessionRepository.findByWardIdAndSessionDate(W, TODAY)).thenReturn(List.of(
                finished(W, TODAY, CompanionSlot.S09, CompanionMood.DEPRESSED, 5), // 연결 전 - 숨김
                finished(W, TODAY, CompanionSlot.S12, CompanionMood.JOY, 4)));
        when(sessionRepository.findFinishedSince(eq(W), eq(CompanionSessionStatus.FINISHED), any()))
                .thenReturn(List.of());

        TodayResponse today = service.today(G, W);

        assertThat(today.current().slot()).isEqualTo("12:00");
        assertThat(today.current().mood()).isEqualTo("JOY");
        assertThat(today.current().summary()).isNull();
        assertThat(today.answeredCount()).isEqualTo(1);
        assertThat(today.nextSlot()).isEqualTo("15:00");
        assertThat(today.slots()).extracting("slot", "status", "mood").containsExactly(
                org.assertj.core.groups.Tuple.tuple("09:00", "MISSED", null),
                org.assertj.core.groups.Tuple.tuple("12:00", "DONE", "JOY"),
                org.assertj.core.groups.Tuple.tuple("15:00", "UPCOMING", null),
                org.assertj.core.groups.Tuple.tuple("18:00", "UPCOMING", null),
                org.assertj.core.groups.Tuple.tuple("21:00", "UPCOMING", null));
        assertThat(today.attention()).isNull();
    }

    @Test
    @DisplayName("오늘 마친 대화가 없으면 지금 기분은 null(확인할 수 없어요), 걱정·우울 3일이면 확인할 일")
    void attention() {
        when(connectionService.findActiveConnectedAt(G, W)).thenReturn(Optional.of(OffsetDateTime.MIN));
        when(sessionRepository.findByWardIdAndSessionDate(W, TODAY)).thenReturn(List.of());
        when(sessionRepository.findFinishedSince(eq(W), eq(CompanionSessionStatus.FINISHED), any())).thenReturn(List.of(
                finished(W, TODAY.minusDays(1), CompanionSlot.S21, CompanionMood.WORRY, 3),
                finished(W, TODAY.minusDays(2), CompanionSlot.S21, CompanionMood.DEPRESSED, 3),
                finished(W, TODAY.minusDays(3), CompanionSlot.S21, CompanionMood.WORRY, 3)));

        TodayResponse today = service.today(G, W);

        assertThat(today.current()).isNull();
        assertThat(today.attention().days()).isEqualTo(3);
        assertThat(today.attention().mood()).isEqualTo("WORRY");
    }

    @Test
    @DisplayName("D-1: 확인할 일(주의)은 지금 연결 이후 대화로만 센다 - 연결 전 걱정 2일 + 연결 후 1일이면 null")
    void attentionCountsOnlySinceConnection() {
        when(connectionService.findActiveConnectedAt(G, W)).thenReturn(Optional.of(
                TODAY.minusDays(1).atStartOfDay().atOffset(java.time.ZoneOffset.ofHours(9))));
        when(sessionRepository.findByWardIdAndSessionDate(W, TODAY)).thenReturn(List.of());
        when(sessionRepository.findFinishedSince(eq(W), eq(CompanionSessionStatus.FINISHED), any())).thenReturn(List.of(
                finished(W, TODAY.minusDays(1), CompanionSlot.S21, CompanionMood.WORRY, 3),
                finished(W, TODAY.minusDays(2), CompanionSlot.S21, CompanionMood.DEPRESSED, 3),
                finished(W, TODAY.minusDays(3), CompanionSlot.S21, CompanionMood.WORRY, 3)));

        TodayResponse today = service.today(G, W);

        assertThat(today.attention()).as("연결 이후 1일뿐이라 주의 아님").isNull();
    }
}

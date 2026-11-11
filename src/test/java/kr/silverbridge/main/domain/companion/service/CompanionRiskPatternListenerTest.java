package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.event.CompanionRiskPatternCheckEvent;
import kr.silverbridge.main.domain.companion.listener.CompanionRiskPatternListener;
import kr.silverbridge.main.domain.companion.repository.CompanionRiskPatternNoticeRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static kr.silverbridge.main.domain.companion.service.CompanionRiskPatternTest.alerted;
import static kr.silverbridge.main.domain.companion.service.CompanionRiskPatternTest.turn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 여러 날 위험 누적 알림 리스너 - 스위치, 보호자별 D-1, 7일 1번 선점, 받을 사람 없으면 선점 안 함, 문구에 확률·원문 없음. */
class CompanionRiskPatternListenerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 11);
    private static final String W = "CW0001";
    private static final CompanionRiskPatternCheckEvent EVENT = new CompanionRiskPatternCheckEvent(W, TODAY);

    private CompanionProperties properties;
    private CompanionSessionRepository sessionRepository;
    private CompanionRiskPatternNoticeRepository noticeRepository;
    private ConnectionService connectionService;
    private NotificationDispatcher dispatcher;
    private CompanionRiskPatternListener listener;

    @BeforeEach
    void setUp() {
        properties = new CompanionProperties();
        properties.getRiskPattern().setEnabled(true);
        sessionRepository = mock(CompanionSessionRepository.class);
        noticeRepository = mock(CompanionRiskPatternNoticeRepository.class);
        connectionService = mock(ConnectionService.class);
        dispatcher = mock(NotificationDispatcher.class);
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findById(W)).thenReturn(Optional.empty());
        listener = new CompanionRiskPatternListener(properties, sessionRepository, noticeRepository, connectionService,
                dispatcher, userRepository);
        when(connectionService.getActiveGuardianIds(W)).thenReturn(List.of("GD0001", "GD0002"));
        when(connectionService.findActiveConnectedAt(anyString(), eq(W))).thenReturn(Optional.of(OffsetDateTime.MIN));
        when(noticeRepository.claim(eq(W), eq(TODAY), anyInt(), any(), any())).thenReturn(1);
    }

    private void sessions(CompanionSession... sessions) {
        when(sessionRepository.findByWardIdAndSessionDateBetween(W, TODAY.minusDays(6), TODAY))
                .thenReturn(List.of(sessions));
    }

    @Test
    @DisplayName("두 날 높으면 보호자 전원에게 COMPANION_RISK_PATTERN, 선점 기준일은 오늘 -7일, 문구에 확률·원문 없음")
    void twoDaysNotifiesAllGuardians() {
        sessions(turn(TODAY.minusDays(2), CompanionSlot.S09, "0.9812"), turn(TODAY, CompanionSlot.S12, "0.9734"));

        listener.handleCheck(EVENT);

        verify(noticeRepository).claim(eq(W), eq(TODAY), eq(2), any(), eq(TODAY.minusDays(7)));
        ArgumentCaptor<NotificationContent> content = ArgumentCaptor.forClass(NotificationContent.class);
        verify(dispatcher).dispatch(eq("GD0001"), eq(W), eq(NotificationType.COMPANION_RISK_PATTERN), content.capture());
        verify(dispatcher).dispatch(eq("GD0002"), eq(W), eq(NotificationType.COMPANION_RISK_PATTERN), any());
        assertThat(content.getValue().body()).contains("여러 번").doesNotContain("0.98", "0.97", "확률");
        assertThat(content.getValue().data()).containsEntry("type", "COMPANION_RISK_PATTERN").containsEntry("days", "2")
                .doesNotContainKey("riskProbability");
    }

    @Test
    @DisplayName("7일 안에 이미 보냈으면(선점 짐) 보내지 않는다")
    void claimLostSendsNothing() {
        sessions(turn(TODAY.minusDays(2), CompanionSlot.S09, "0.98"), turn(TODAY, CompanionSlot.S12, "0.98"));
        when(noticeRepository.claim(eq(W), eq(TODAY), anyInt(), any(), any())).thenReturn(0);

        listener.handleCheck(EVENT);

        verifyNoInteractions(dispatcher);
    }

    @Test
    @DisplayName("D-1: 보호자는 지금 연결 이후 대화만 센다 - 연결 전 높은 날만 있는 보호자는 빼고, 아무도 없으면 선점하지 않는다")
    void connectionBeforeExcluded() {
        CompanionSession older = turn(TODAY.minusDays(3), CompanionSlot.S09, "0.99");
        sessions(older, turn(TODAY, CompanionSlot.S12, "0.98"));
        OffsetDateTime connectedYesterday = CompanionTestSessions.at(TODAY.minusDays(1), CompanionSlot.S09, 0);
        when(connectionService.findActiveConnectedAt("GD0001", W)).thenReturn(Optional.of(connectedYesterday));

        listener.handleCheck(EVENT);

        verify(dispatcher, never()).dispatch(eq("GD0001"), any(), any(), any());
        verify(dispatcher).dispatch(eq("GD0002"), eq(W), eq(NotificationType.COMPANION_RISK_PATTERN), any());

        when(connectionService.findActiveConnectedAt("GD0002", W)).thenReturn(Optional.of(connectedYesterday));
        listener.handleCheck(EVENT);
        verify(noticeRepository, org.mockito.Mockito.times(1)).claim(any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("한 날뿐이거나 위험 알림을 받은 날만이면 선점도 발송도 하지 않는다")
    void notQualifiedDoesNothing() {
        sessions(alerted(turn(TODAY.minusDays(2), CompanionSlot.S09, "0.995")),
                alerted(turn(TODAY, CompanionSlot.S12, "0.995")));

        listener.handleCheck(EVENT);

        verify(noticeRepository, never()).claim(any(), any(), anyInt(), any(), any());
        verifyNoInteractions(dispatcher);
    }

    @Test
    @DisplayName("스위치가 꺼지면 조회도 하지 않는다")
    void disabledDoesNothing() {
        properties.getRiskPattern().setEnabled(false);

        listener.handleCheck(EVENT);

        verifyNoInteractions(sessionRepository, noticeRepository, dispatcher);
    }

    @Test
    @DisplayName("한 보호자 발송 실패가 다른 보호자를 막지 않는다")
    void oneFailureIsolated() {
        sessions(turn(TODAY.minusDays(2), CompanionSlot.S09, "0.98"), turn(TODAY, CompanionSlot.S12, "0.98"));
        doThrow(new IllegalStateException("x")).when(dispatcher)
                .dispatch(eq("GD0001"), any(), any(), any());

        listener.handleCheck(EVENT);

        verify(dispatcher).dispatch(eq("GD0002"), eq(W), eq(NotificationType.COMPANION_RISK_PATTERN), any());
    }
}

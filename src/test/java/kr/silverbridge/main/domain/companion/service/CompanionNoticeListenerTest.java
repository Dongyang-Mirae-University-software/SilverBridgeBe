package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.entity.CompanionMood;
import kr.silverbridge.main.domain.companion.entity.CompanionNoticeLog;
import kr.silverbridge.main.domain.companion.entity.CompanionSessionStatus;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.event.CompanionMoodRecordedEvent;
import kr.silverbridge.main.domain.companion.listener.CompanionNoticeListener;
import kr.silverbridge.main.domain.companion.repository.CompanionNoticeLogRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.notification.service.GuardianNotificationPreferenceService;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static kr.silverbridge.main.domain.companion.service.CompanionTestSessions.finished;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 주의 알림 - 3일 이상만, 끈 보호자 제외, 하루 1번(선점), 문구에 병명·원문 없음. */
class CompanionNoticeListenerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);
    private static final String W = "CW0001";

    private CompanionSessionRepository sessionRepository;
    private CompanionNoticeLogRepository noticeLogRepository;
    private ConnectionService connectionService;
    private GuardianNotificationPreferenceService preferenceService;
    private NotificationDispatcher dispatcher;
    private CompanionNoticeListener listener;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(CompanionSessionRepository.class);
        noticeLogRepository = mock(CompanionNoticeLogRepository.class);
        connectionService = mock(ConnectionService.class);
        preferenceService = mock(GuardianNotificationPreferenceService.class);
        dispatcher = mock(NotificationDispatcher.class);
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findById(W)).thenReturn(Optional.empty());
        listener = new CompanionNoticeListener(sessionRepository, noticeLogRepository, connectionService,
                preferenceService, dispatcher, userRepository);
        when(connectionService.getActiveGuardianIds(W)).thenReturn(List.of("GD0001", "GD0002"));
        when(preferenceService.companionNoticeDisabledGuardians()).thenReturn(Set.of("GD0002"));
        // 기본: 오래전에 맺은 연결(연결 이후 대화 전부가 보인다)
        when(connectionService.findActiveConnectedAt(anyString(), eq(W))).thenReturn(Optional.of(OffsetDateTime.MIN));
    }

    @Test
    @DisplayName("3일째면 선점하고 켠 보호자에게만 보낸다 - 문구에 병명·원문이 없다")
    void noticeOnThirdDay() {
        concerningDays(3);

        listener.handleMoodRecorded(new CompanionMoodRecordedEvent(W, TODAY));

        ArgumentCaptor<CompanionNoticeLog> log = ArgumentCaptor.forClass(CompanionNoticeLog.class);
        verify(noticeLogRepository).saveAndFlush(log.capture());
        assertThat(log.getValue().getDays()).isEqualTo(3);
        ArgumentCaptor<NotificationContent> content = ArgumentCaptor.forClass(NotificationContent.class);
        verify(dispatcher).dispatch(eq("GD0001"), eq(W), eq(NotificationType.COMPANION_NOTICE), content.capture());
        verify(dispatcher, never()).dispatch(eq("GD0002"), anyString(), any(), any());
        assertThat(content.getValue().body()).contains("3일째").doesNotContain("우울증").doesNotContain("질환");
    }

    @Test
    @DisplayName("2일이면 보내지 않고 선점도 하지 않는다")
    void noNoticeOnSecondDay() {
        concerningDays(2);

        listener.handleMoodRecorded(new CompanionMoodRecordedEvent(W, TODAY));

        verify(noticeLogRepository, never()).saveAndFlush(any());
        verify(dispatcher, never()).dispatch(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("오늘 이미 보냈으면(선점 실패) 다시 보내지 않는다")
    void oncePerDay() {
        concerningDays(4);
        when(noticeLogRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));

        listener.handleMoodRecorded(new CompanionMoodRecordedEvent(W, TODAY));

        verify(dispatcher, never()).dispatch(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("D-1: 연결 이후 2일뿐인 보호자는 빼고, 오래 연결한 보호자에게만 보낸다(연결 전 기분 일수를 알리지 않는다)")
    void excludesGuardianConnectedAfterStreakStarted() {
        concerningDays(3);
        when(connectionService.getActiveGuardianIds(W)).thenReturn(List.of("GD0001", "GD0003"));
        when(connectionService.findActiveConnectedAt("GD0003", W)).thenReturn(Optional.of(kstMidnight(TODAY.minusDays(1))));

        listener.handleMoodRecorded(new CompanionMoodRecordedEvent(W, TODAY));

        verify(dispatcher).dispatch(eq("GD0001"), eq(W), eq(NotificationType.COMPANION_NOTICE), any());
        verify(dispatcher, never()).dispatch(eq("GD0003"), anyString(), any(), any());
    }

    @Test
    @DisplayName("D-1: 받을 수 있는 보호자 모두 연결 이후 3일이 안 되면 선점도 발송도 하지 않는다")
    void noClaimWhenNoGuardianReachesNoticeSinceConnection() {
        concerningDays(5);
        when(connectionService.getActiveGuardianIds(W)).thenReturn(List.of("GD0001"));
        when(connectionService.findActiveConnectedAt("GD0001", W)).thenReturn(Optional.of(kstMidnight(TODAY)));

        listener.handleMoodRecorded(new CompanionMoodRecordedEvent(W, TODAY));

        verify(noticeLogRepository, never()).saveAndFlush(any());
        verify(dispatcher, never()).dispatch(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("D-1: 보호자마다 자기 연결 이후 일수로 보내고, 기록 일수는 그중 최댓값")
    void eachGuardianGetsOwnDaysSinceConnection() {
        concerningDays(4);
        when(connectionService.getActiveGuardianIds(W)).thenReturn(List.of("GD0001", "GD0003"));
        when(connectionService.findActiveConnectedAt("GD0003", W)).thenReturn(Optional.of(kstMidnight(TODAY.minusDays(2))));

        listener.handleMoodRecorded(new CompanionMoodRecordedEvent(W, TODAY));

        ArgumentCaptor<NotificationContent> first = ArgumentCaptor.forClass(NotificationContent.class);
        ArgumentCaptor<NotificationContent> second = ArgumentCaptor.forClass(NotificationContent.class);
        verify(dispatcher).dispatch(eq("GD0001"), eq(W), eq(NotificationType.COMPANION_NOTICE), first.capture());
        verify(dispatcher).dispatch(eq("GD0003"), eq(W), eq(NotificationType.COMPANION_NOTICE), second.capture());
        assertThat(first.getValue().body()).contains("4일째");
        assertThat(second.getValue().body()).contains("3일째");
        ArgumentCaptor<CompanionNoticeLog> log = ArgumentCaptor.forClass(CompanionNoticeLog.class);
        verify(noticeLogRepository).saveAndFlush(log.capture());
        assertThat(log.getValue().getDays()).isEqualTo(4);
    }

    @Test
    @DisplayName("그사이 연결이 끝난 보호자(연결 시각 없음)에게는 보내지 않는다")
    void skipsGuardianWhoseConnectionEnded() {
        concerningDays(3);
        when(connectionService.getActiveGuardianIds(W)).thenReturn(List.of("GD0001", "GD0003"));
        when(connectionService.findActiveConnectedAt("GD0003", W)).thenReturn(Optional.empty());

        listener.handleMoodRecorded(new CompanionMoodRecordedEvent(W, TODAY));

        verify(dispatcher).dispatch(eq("GD0001"), eq(W), eq(NotificationType.COMPANION_NOTICE), any());
        verify(dispatcher, never()).dispatch(eq("GD0003"), anyString(), any(), any());
    }

    private static OffsetDateTime kstMidnight(LocalDate date) {
        return date.atStartOfDay().atOffset(java.time.ZoneOffset.ofHours(9));
    }

    private void concerningDays(int days) {
        List<kr.silverbridge.main.domain.companion.entity.CompanionSession> sessions = new java.util.ArrayList<>();
        for (int i = 0; i < days; i++) {
            sessions.add(finished(W, TODAY.minusDays(i), CompanionSlot.S21, CompanionMood.WORRY, 3));
        }
        when(sessionRepository.findFinishedSince(eq(W), eq(CompanionSessionStatus.FINISHED), any()))
                .thenReturn(sessions);
    }
}

package kr.silverbridge.main.domain.checkin.service;

import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;
import kr.silverbridge.main.domain.checkin.repository.CheckinNotifyLogRepository;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckinGuardianAlertServiceTest {

    private static final String WARD = "wA1b2C";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 1);

    private final ConnectionService connectionService = mock(ConnectionService.class);
    private final CheckinNotifyLogRepository logRepository = mock(CheckinNotifyLogRepository.class);
    private final NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final GuardianNotificationPreferenceService preferenceService = mock(GuardianNotificationPreferenceService.class);
    private final CheckinGuardianAlertService service = new CheckinGuardianAlertService(connectionService, logRepository,
            dispatcher, userRepository, preferenceService);

    @BeforeEach
    void setUp() {
        when(connectionService.getActiveGuardianIds(WARD)).thenReturn(List.of("g1", "g2"));
        when(userRepository.findById(WARD)).thenReturn(Optional.empty());
        when(logRepository.claimGuardianAlert(anyString(), eq(WARD), eq(DAY), anyString(), anyString())).thenReturn(1);
    }

    @Test
    @DisplayName("위험: ACTIVE 보호자 전원에게 CHECKIN_RISK, 문구는 단정하지 않고 대화 내용을 싣지 않는다")
    void risk() {
        assertThat(service.sendRisk(WARD, DAY, CheckinSlot.AT_15)).isEqualTo(2);

        ArgumentCaptor<NotificationContent> content = ArgumentCaptor.forClass(NotificationContent.class);
        verify(dispatcher).dispatch(eq("g1"), eq(WARD), eq(NotificationType.CHECKIN_RISK), content.capture());
        assertThat(content.getValue().title()).startsWith("[긴급]");
        assertThat(content.getValue().body()).contains("도움이 필요할 수 있는");
        assertThat(content.getValue().data()).containsEntry("slot", "15:00").containsKeys("type", "wardId", "checkDate")
                .doesNotContainKeys("summary", "transcript");
        assertThat(content.getValue().smsFallbackText()).isNotBlank();
    }

    @Test
    @DisplayName("이미 선점된 보호자는 건너뛰고, 한 보호자 실패가 다른 보호자를 막지 않는다")
    void claimAndIsolation() {
        when(logRepository.claimGuardianAlert("g1", WARD, DAY, "15:00", "ATTENTION")).thenReturn(0);
        assertThat(service.sendAttention(WARD, DAY, CheckinSlot.AT_15)).isEqualTo(1);
        verify(dispatcher, never()).dispatch(eq("g1"), anyString(), any(), any());

        when(logRepository.claimGuardianAlert("g1", WARD, DAY, "15:00", "RISK")).thenReturn(1);
        doThrow(new RuntimeException("x")).when(dispatcher).dispatch(eq("g1"), anyString(), eq(NotificationType.CHECKIN_RISK), any());
        assertThat(service.sendRisk(WARD, DAY, CheckinSlot.AT_15)).isEqualTo(1);
        verify(dispatcher).dispatch(eq("g2"), eq(WARD), eq(NotificationType.CHECKIN_RISK), any());
    }

    @Test
    @DisplayName("미응답: 끈 보호자·그날 이미 받은 보호자 제외(하루 한 번)")
    void missed() {
        when(preferenceService.checkinMissedDisabledGuardians()).thenReturn(Set.of("g1"));
        when(logRepository.alertedOnDate("g2", WARD, DAY, "MISSED")).thenReturn(true);

        assertThat(service.sendMissed(WARD, DAY, CheckinSlot.AT_09)).isZero();
        verify(dispatcher, never()).dispatch(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("미응답: 수신 설정을 읽지 못하면 이번 회차는 보내지 않는다(꺼 둔 사람에게 가지 않게)")
    void missedPreferenceFailure() {
        when(preferenceService.checkinMissedDisabledGuardians()).thenThrow(new RuntimeException("db"));
        assertThat(service.sendMissed(WARD, DAY, CheckinSlot.AT_09)).isZero();
        verify(logRepository, never()).claimGuardianAlert(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("시각 표기: 오전 9시·낮 12시·오후 3시")
    void hourLabel() {
        assertThat(CheckinGuardianAlertService.hourLabel(CheckinSlot.AT_09)).isEqualTo("오전 9시");
        assertThat(CheckinGuardianAlertService.hourLabel(CheckinSlot.AT_12)).isEqualTo("낮 12시");
        assertThat(CheckinGuardianAlertService.hourLabel(CheckinSlot.AT_15)).isEqualTo("오후 3시");
    }
}

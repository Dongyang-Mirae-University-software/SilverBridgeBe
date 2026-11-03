package kr.silverbridge.main.domain.companion.listener;

import kr.silverbridge.main.domain.companion.event.CompanionRiskDetectedEvent;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 안부 말벗 위험 알림 - ACTIVE 보호자 전원, 강제 종류, 문구에 원문·구체 내용 없음, 한 명 실패가 나머지를 막지 않음. */
class CompanionRiskNotificationListenerTest {

    private final ConnectionService connectionService = mock(ConnectionService.class);
    private final NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final CompanionRiskNotificationListener listener =
            new CompanionRiskNotificationListener(connectionService, dispatcher, userRepository);

    @Test
    @DisplayName("ACTIVE 보호자마다 COMPANION_RISK를 보내고, 한 명이 실패해도 다음 보호자에게 보낸다")
    void notifiesEachGuardian() {
        when(connectionService.getActiveGuardianIds("CW0001")).thenReturn(List.of("G1", "G2"));
        when(userRepository.findById("CW0001")).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("boom")).when(dispatcher)
                .dispatch(eq("G1"), anyString(), any(NotificationType.class), any());
        ArgumentCaptor<NotificationContent> content = ArgumentCaptor.forClass(NotificationContent.class);

        listener.handleRisk(new CompanionRiskDetectedEvent("CW0001", 7L));

        verify(dispatcher).dispatch(eq("G2"), eq("CW0001"), eq(NotificationType.COMPANION_RISK), content.capture());
        assertThat(content.getValue().body()).contains("피보호자님").doesNotContain("자살").doesNotContain("우울");
        assertThat(content.getValue().data()).containsEntry("type", "COMPANION_RISK").containsEntry("sessionId", "7");
    }

    @Test
    @DisplayName("보호자가 없으면 보내지 않는다")
    void noGuardian() {
        when(connectionService.getActiveGuardianIds("CW0001")).thenReturn(List.of());

        listener.handleRisk(new CompanionRiskDetectedEvent("CW0001", 7L));

        verify(dispatcher, never()).dispatch(anyString(), anyString(), any(NotificationType.class), any());
    }
}

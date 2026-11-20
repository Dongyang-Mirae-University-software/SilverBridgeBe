package kr.silverbridge.main.domain.medication.listener;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.medication.event.MedicationIntakeChangedEvent;
import kr.silverbridge.main.global.websocket.WebSocketEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MedicationIntakeNotificationListenerTest {

    @Mock
    private ConnectionService connectionService;
    @Mock
    private WebSocketEventPublisher webSocketEventPublisher;
    @InjectMocks
    private MedicationIntakeNotificationListener listener;

    @Test
    @DisplayName("medication-taken WS는 원본 medicationName과 화면용 medicationDisplayName을 함께 싣는다")
    @SuppressWarnings("unchecked")
    void payloadCarriesDisplayName() {
        given(connectionService.getActiveGuardianIds("ward01")).willReturn(List.of());

        listener.handleIntakeChanged(new MedicationIntakeChangedEvent(
                12L, "ward01", "쎄레브렉스캡슐200밀리그램", LocalDate.of(2026, 11, 20), false, null));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(webSocketEventPublisher).sendToUser(eq("ward01"), eq("medication-taken"), payload.capture());
        Map<String, String> sent = (Map<String, String>) payload.getValue();
        assertThat(sent)
                .containsEntry("medicationName", "쎄레브렉스캡슐200밀리그램")
                .containsEntry("medicationDisplayName", "쎄레브렉스캡슐");
    }
}

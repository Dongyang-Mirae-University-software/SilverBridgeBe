package kr.silverbridge.main.domain.sos.listener;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.sos.entity.SosAckStatus;
import kr.silverbridge.main.domain.sos.event.SosAcknowledgedEvent;
import kr.silverbridge.main.global.websocket.WebSocketEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SosAckNotificationListener 단위 테스트.
 *
 * <p>AFTER_COMMIT 핸들러를 직접 호출해 ① 수신자 = ACTIVE 보호자 전원 + 피보호자 본인 ② WebSocket만 발송
 * (푸시·문자 없음 — 협력 빈 자체가 없다) ③ 페이로드 내용 ④ 보호자가 없어도 피보호자에게는 발송을 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class SosAckNotificationListenerTest {

    @Mock private ConnectionService connectionService;
    @Mock private WebSocketEventPublisher webSocketEventPublisher;

    @InjectMocks private SosAckNotificationListener listener;

    private static final String WARD_ID = "WD0001";
    private static final String GUARDIAN_ID = "GD0001";
    private final SosAcknowledgedEvent event = new SosAcknowledgedEvent(
            7L, WARD_ID, GUARDIAN_ID, "남궁명진", SosAckStatus.SAFE_CONFIRMED);

    @Test
    @DisplayName("ACTIVE 보호자 전원 + 피보호자 본인에게 WS(sos-acknowledged) 발송 — 처리한 보호자 본인도 포함(기기 동기화)")
    void handleSosAcknowledged_수신자() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_ID, "GD0002"));

        listener.handleSosAcknowledged(event);

        verify(webSocketEventPublisher).sendToUser(eq(GUARDIAN_ID), eq("sos-acknowledged"), anyMap());
        verify(webSocketEventPublisher).sendToUser(eq("GD0002"), eq("sos-acknowledged"), anyMap());
        verify(webSocketEventPublisher).sendToUser(eq(WARD_ID), eq("sos-acknowledged"), anyMap());
        verifyNoMoreInteractions(webSocketEventPublisher);
    }

    @Test
    @DisplayName("페이로드에 이력ID·피보호자·처리결과·처리자(ID·이름)를 담는다")
    void handleSosAcknowledged_페이로드() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(GUARDIAN_ID));

        listener.handleSosAcknowledged(event);

        ArgumentCaptor<Map<String, String>> payloadCaptor = ArgumentCaptor.captor();
        verify(webSocketEventPublisher).sendToUser(eq(WARD_ID), eq("sos-acknowledged"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("sosEventId", "7")
                .containsEntry("wardId", WARD_ID)
                .containsEntry("ackStatus", "SAFE_CONFIRMED")
                .containsEntry("acknowledgedBy", GUARDIAN_ID)
                .containsEntry("acknowledgedByName", "남궁명진");
    }

    @Test
    @DisplayName("ACTIVE 보호자가 없어도 피보호자 본인에게는 발송한다")
    void handleSosAcknowledged_보호자없음() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of());

        listener.handleSosAcknowledged(event);

        verify(webSocketEventPublisher).sendToUser(eq(WARD_ID), eq("sos-acknowledged"), anyMap());
        verifyNoMoreInteractions(webSocketEventPublisher);
    }
}

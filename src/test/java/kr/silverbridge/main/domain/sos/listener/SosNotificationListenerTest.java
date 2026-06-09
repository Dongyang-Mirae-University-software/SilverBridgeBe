package kr.silverbridge.main.domain.sos.listener;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.sos.event.SosTriggeredEvent;
import kr.silverbridge.main.domain.sos.service.SosNotificationCooldown;
import kr.silverbridge.main.global.websocket.WebSocketEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SosNotificationListener 단위 테스트.
 *
 * AFTER_COMMIT 핸들러를 직접 호출하여 ① ACTIVE 보호자 전원 발송 ② 필수 타입(WARD_SOS) 디스패치(설정 무시)
 * ③ 보호자 0명 처리 ④ 한 보호자 실패가 나머지를 막지 않는 실패 격리 ⑤ 쿨다운 내 재요청 시 알림 생략을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class SosNotificationListenerTest {

    @Mock private ConnectionService connectionService;
    @Mock private WebSocketEventPublisher webSocketEventPublisher;
    @Mock private NotificationDispatcher notificationDispatcher;
    @Mock private SosNotificationCooldown cooldown;

    @InjectMocks private SosNotificationListener listener;

    private static final String WARD_ID = "WD0001";
    private static final long SOS_EVENT_ID = 7L;
    private final SosTriggeredEvent event = new SosTriggeredEvent(WARD_ID, SOS_EVENT_ID, "김순자");

    @Test
    @DisplayName("ACTIVE 보호자 전원에게 WS(sos-triggered) + 디스패처(WARD_SOS) 긴급 알림 발송")
    void handleSosTriggered_보호자전원발송() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of("GD0001", "GD0002"));
        when(cooldown.tryAcquire(WARD_ID)).thenReturn(true);

        listener.handleSosTriggered(event);

        for (String guardianId : List.of("GD0001", "GD0002")) {
            verify(webSocketEventPublisher).sendToUser(eq(guardianId), eq("sos-triggered"), anyMap());
            verify(notificationDispatcher).dispatch(
                    eq(guardianId), eq(NotificationType.WARD_SOS), any(NotificationContent.class));
        }
    }

    @Test
    @DisplayName("긴급 알림은 필수 타입(WARD_SOS)으로 디스패치 → 보호자 알림 설정 무시. 문구·data 검증")
    void handleSosTriggered_필수알림_설정무시() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of("GD0001"));
        when(cooldown.tryAcquire(WARD_ID)).thenReturn(true);

        listener.handleSosTriggered(event);

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(eq("GD0001"), eq(NotificationType.WARD_SOS), captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("긴급 SOS");
        assertThat(captor.getValue().body()).isEqualTo("김순자님이 긴급 도움을 요청했습니다.");
        assertThat(captor.getValue().data()).containsEntry("type", "WARD_SOS");

        // 디스패처가 사용자 설정을 무시하고 강제 발송하도록 보장하는 분류(필수 알림)
        assertThat(NotificationType.WARD_SOS.isMandatory()).isTrue();
    }

    @Test
    @DisplayName("연결된 ACTIVE 보호자가 없으면 이력만 남고 발송 없음")
    void handleSosTriggered_보호자없음() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of());

        listener.handleSosTriggered(event);

        verifyNoInteractions(webSocketEventPublisher, notificationDispatcher);
    }

    @Test
    @DisplayName("한 보호자 알림 실패가 나머지 보호자 발송을 막지 않는다 (실패 격리)")
    void handleSosTriggered_실패격리() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of("GD0001", "GD0002"));
        when(cooldown.tryAcquire(WARD_ID)).thenReturn(true);
        doThrow(new RuntimeException("FCM down"))
                .when(notificationDispatcher).dispatch(eq("GD0001"), eq(NotificationType.WARD_SOS), any());

        listener.handleSosTriggered(event);

        // GD0001 발송 실패에도 GD0002는 정상 발송됨
        verify(webSocketEventPublisher).sendToUser(eq("GD0002"), eq("sos-triggered"), anyMap());
        verify(notificationDispatcher).dispatch(eq("GD0002"), eq(NotificationType.WARD_SOS), any());
    }

    @Test
    @DisplayName("쿨다운 내 재요청이면 알림을 생략한다 (이력은 서비스에서 이미 저장됨)")
    void handleSosTriggered_쿨다운_알림생략() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of("GD0001"));
        when(cooldown.tryAcquire(WARD_ID)).thenReturn(false); // 직전 발송 후 쿨다운 내

        listener.handleSosTriggered(event);

        // 알림만 생략 — 보호자에게 WS/디스패처 발송 없음. (sos_events 이력은 SosService 책임이라 여기서 영향 없음)
        verifyNoInteractions(webSocketEventPublisher, notificationDispatcher);
    }
}

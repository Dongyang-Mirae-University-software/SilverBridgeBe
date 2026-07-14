package kr.silverbridge.main.domain.anomaly.listener;

import kr.silverbridge.main.domain.anomaly.event.AnomalyDetectedEvent;
import kr.silverbridge.main.domain.anomaly.service.AnomalyNotificationCooldown;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.global.enums.DetectedType;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AnomalyNotificationListener 단위 테스트.
 *
 * 검증: 수신자 구성(ACTIVE 보호자 전원 + 피보호자 본인) / 쿨다운 시 생략 / 수신자별 실패 격리 /
 * 본인·보호자 문구 구분.
 */
@ExtendWith(MockitoExtension.class)
class AnomalyNotificationListenerTest {

    private static final String WARD_ID = "WD0001";
    private static final String SESSION_ID = "ward_a9cC5f_k3m";

    @Mock private ConnectionService connectionService;
    @Mock private WebSocketEventPublisher webSocketEventPublisher;
    @Mock private NotificationDispatcher notificationDispatcher;
    @Mock private AnomalyNotificationCooldown cooldown;

    @InjectMocks private AnomalyNotificationListener listener;

    private final AnomalyDetectedEvent event =
            new AnomalyDetectedEvent(7L, WARD_ID, "김순자", SESSION_ID, "거실", DetectedType.FIRE);

    @Test
    @DisplayName("ACTIVE 보호자 전원과 피보호자 본인 모두에게 발송한다")
    void 보호자전원과_본인에게_발송() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of("GD0001", "GD0002"));
        when(cooldown.tryAcquire(anyString(), eq(SESSION_ID), eq(DetectedType.FIRE), anyBoolean())).thenReturn(true);

        listener.handleAnomalyDetected(event);

        verify(notificationDispatcher).dispatch(eq("GD0001"), eq(NotificationType.ANOMALY_DETECTED), any());
        verify(notificationDispatcher).dispatch(eq("GD0002"), eq(NotificationType.ANOMALY_DETECTED), any());
        verify(notificationDispatcher).dispatch(eq(WARD_ID), eq(NotificationType.ANOMALY_DETECTED), any());
        verify(webSocketEventPublisher).sendToUser(eq(WARD_ID), eq("anomaly-detected"), any());
    }

    @Test
    @DisplayName("보호자에겐 '누구 댁 어디' 문구, 본인에겐 대피 안내 문구를 보낸다")
    void 문구_수신자별_구분() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of("GD0001"));
        when(cooldown.tryAcquire(anyString(), eq(SESSION_ID), eq(DetectedType.FIRE), anyBoolean())).thenReturn(true);

        listener.handleAnomalyDetected(event);

        ArgumentCaptor<NotificationContent> guardian = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(eq("GD0001"), any(), guardian.capture());
        assertThat(guardian.getValue().title()).isEqualTo("이상 상황 감지");
        assertThat(guardian.getValue().body()).isEqualTo("김순자님 댁 거실에서 화재가 감지되었습니다.");
        assertThat(guardian.getValue().data())
                .containsEntry("type", "ANOMALY_DETECTED")
                .containsEntry("anomalyEventId", "7");

        ArgumentCaptor<NotificationContent> self = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(eq(WARD_ID), any(), self.capture());
        assertThat(self.getValue().body()).isEqualTo("거실에서 화재가 감지되었습니다. 안전한 곳으로 대피해 주세요.");
    }

    @Test
    @DisplayName("쿨다운에 걸린 수신자에게는 발송을 생략한다(다른 수신자는 정상 발송)")
    void 쿨다운_수신자만_생략() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of("GD0001"));
        when(cooldown.tryAcquire(eq("GD0001"), eq(SESSION_ID), eq(DetectedType.FIRE), eq(false))).thenReturn(false);
        when(cooldown.tryAcquire(eq(WARD_ID), eq(SESSION_ID), eq(DetectedType.FIRE), eq(true))).thenReturn(true);

        listener.handleAnomalyDetected(event);

        verify(notificationDispatcher, never()).dispatch(eq("GD0001"), any(), any());
        verify(webSocketEventPublisher, never()).sendToUser(eq("GD0001"), anyString(), any());
        verify(notificationDispatcher).dispatch(eq(WARD_ID), any(), any());
    }

    @Test
    @DisplayName("한 수신자 발송이 실패해도 나머지 수신자 발송은 진행된다(실패 격리)")
    void 수신자_실패격리() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of("GD0001"));
        when(cooldown.tryAcquire(anyString(), eq(SESSION_ID), eq(DetectedType.FIRE), anyBoolean())).thenReturn(true);
        doThrow(new RuntimeException("FCM 장애"))
                .when(notificationDispatcher).dispatch(eq("GD0001"), any(), any());

        listener.handleAnomalyDetected(event);

        verify(notificationDispatcher).dispatch(eq(WARD_ID), any(), any());
    }

    @Test
    @DisplayName("연결된 보호자가 없어도 피보호자 본인에게는 발송한다")
    void 보호자없어도_본인발송() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of());
        when(cooldown.tryAcquire(eq(WARD_ID), eq(SESSION_ID), eq(DetectedType.FIRE), eq(true))).thenReturn(true);

        listener.handleAnomalyDetected(event);

        verify(notificationDispatcher).dispatch(eq(WARD_ID), eq(NotificationType.ANOMALY_DETECTED), any());
    }
}

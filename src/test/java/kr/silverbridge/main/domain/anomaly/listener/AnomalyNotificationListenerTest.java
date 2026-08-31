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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

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
 * 본인·보호자 문구 구분 / 감지 시각 표기(KST 변환·null 대체 — 알림톡 템플릿 변수).
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

    private static final Long INCIDENT_ID = 37L;

    /** AI analyzedAt은 UTC — 표시는 KST(+9)라 05:20Z → 14:20이어야 한다. */
    private static final OffsetDateTime DETECTED_AT =
            OffsetDateTime.of(2026, 7, 23, 5, 20, 0, 0, ZoneOffset.UTC);

    private final AnomalyDetectedEvent event =
            new AnomalyDetectedEvent(7L, INCIDENT_ID, WARD_ID, "김순자", SESSION_ID, "거실", DetectedType.FIRE, DETECTED_AT);

    @Test
    @DisplayName("ACTIVE 보호자 전원과 피보호자 본인 모두에게 발송한다")
    void 보호자전원과_본인에게_발송() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of("GD0001", "GD0002"));
        when(cooldown.tryAcquire(anyString(), eq(SESSION_ID), eq(DetectedType.FIRE), anyBoolean())).thenReturn(true);

        listener.handleAnomalyDetected(event);

        verify(notificationDispatcher).dispatch(eq("GD0001"), eq(NotificationType.ANOMALY_DETECTED), any());
        verify(notificationDispatcher).dispatch(eq("GD0002"), eq(NotificationType.ANOMALY_DETECTED), any());
        verify(notificationDispatcher).dispatch(eq(WARD_ID), eq(NotificationType.ANOMALY_DETECTED_SELF), any());
        verify(webSocketEventPublisher).sendToUser(eq(WARD_ID), eq("anomaly-detected"), any());
    }

    @Test
    @DisplayName("본인은 별도 알림 종류로 보낸다(보호자용 승인 알림톡 템플릿이 본인에게 선택되지 않도록)")
    void 본인은_별도_알림종류로_발송() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of("GD0001"));
        when(cooldown.tryAcquire(anyString(), eq(SESSION_ID), eq(DetectedType.FIRE), anyBoolean())).thenReturn(true);

        listener.handleAnomalyDetected(event);

        // 본인에게 ANOMALY_DETECTED(보호자용)로 나가면 알림톡 문구가 어긋난다 = 카카오 채널 제재 사유
        verify(notificationDispatcher, never()).dispatch(eq(WARD_ID), eq(NotificationType.ANOMALY_DETECTED), any());
        verify(notificationDispatcher, never())
                .dispatch(eq("GD0001"), eq(NotificationType.ANOMALY_DETECTED_SELF), any());

        // 클라이언트 계약(data["type"])은 수신자와 무관하게 그대로 유지한다
        ArgumentCaptor<NotificationContent> self = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(eq(WARD_ID), eq(NotificationType.ANOMALY_DETECTED_SELF), self.capture());
        assertThat(self.getValue().data()).containsEntry("type", "ANOMALY_DETECTED");
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
                .containsEntry("anomalyEventId", "7")
                .containsEntry("detectedAt", "2026-07-23 14:20");   // UTC 05:20 → KST 14:20

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
    @DisplayName("AI 분석 시각이 없어도(fallback 페이로드) 감지 시각을 빈 값으로 보내지 않는다")
    void 분석시각없으면_발송시각으로_표시대체() {
        AnomalyDetectedEvent noAnalyzedAt =
                new AnomalyDetectedEvent(7L, INCIDENT_ID, WARD_ID, "김순자", SESSION_ID, "거실", DetectedType.FIRE, null);
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of());
        when(cooldown.tryAcquire(eq(WARD_ID), eq(SESSION_ID), eq(DetectedType.FIRE), eq(true))).thenReturn(true);

        listener.handleAnomalyDetected(noAnalyzedAt);

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(eq(WARD_ID), any(), captor.capture());
        // 승인 템플릿의 #{detectedAt}이 빈 문자열로 나가면 "감지 시각: "만 발송된다 (분 경계 flaky 방지로 형식만 검증)
        assertThat(captor.getValue().data().get("detectedAt")).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
    }

    @Test
    @DisplayName("연결된 보호자가 없어도 피보호자 본인에게는 발송한다")
    void 보호자없어도_본인발송() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of());
        when(cooldown.tryAcquire(eq(WARD_ID), eq(SESSION_ID), eq(DetectedType.FIRE), eq(true))).thenReturn(true);

        listener.handleAnomalyDetected(event);

        verify(notificationDispatcher).dispatch(eq(WARD_ID), eq(NotificationType.ANOMALY_DETECTED_SELF), any());
    }

    @Test
    @DisplayName("알림 payload에 상황 식별자(incidentId)를 싣는다 — 보호자가 알림에서 바로 오탐 응답을 하려면 필요하다")
    void payload_carriesIncidentId() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of("GD0001"));
        when(cooldown.tryAcquire(anyString(), eq(SESSION_ID), eq(DetectedType.FIRE), anyBoolean())).thenReturn(true);

        listener.handleAnomalyDetected(event);

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(eq("GD0001"), eq(NotificationType.ANOMALY_DETECTED), captor.capture());
        Map<String, String> data = captor.getValue().data();

        assertThat(data.get("incidentId")).isEqualTo("37");
        // 기존 키가 하나도 사라지지 않아야 한다 — Map.of(10쌍 상한) → Map.ofEntries 교체로 늘린 자리다.
        // 알림톡 승인 템플릿의 변수 바인딩이 이 키 이름에 걸려 있어 이름이 바뀌면 발송 문구가 비어 나간다.
        assertThat(data).containsKeys("type", "wardId", "wardName", "location", "sessionId",
                "detectedType", "detectedTypeLabel", "detectedAt", "anomalyEventId", "incidentId");
        assertThat(data.get("type")).isEqualTo(NotificationType.ANOMALY_DETECTED.name());
    }
}

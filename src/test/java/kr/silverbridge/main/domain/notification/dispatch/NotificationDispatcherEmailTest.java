package kr.silverbridge.main.domain.notification.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import kr.silverbridge.main.domain.notification.channel.ChannelFailureReason;
import kr.silverbridge.main.domain.notification.channel.ChannelResult;
import kr.silverbridge.main.domain.notification.channel.NotificationChannel;
import kr.silverbridge.main.domain.notification.channel.NotificationChannelType;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.channel.NotificationRecipient;
import kr.silverbridge.main.domain.notification.entity.NotificationLogResult;
import kr.silverbridge.main.domain.notification.service.NotificationLogService;
import kr.silverbridge.main.domain.notification.service.NotificationSettingService;
import kr.silverbridge.main.global.enums.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 이메일 채널이 등록된 디스패처의 라우팅(2026-10-13). 긴급 알림이 메일로 가지 않고 문자 대체가 유지됨을 고정한다. */
@ExtendWith(MockitoExtension.class)
class NotificationDispatcherEmailTest {

    @Mock private NotificationChannel fcmChannel;
    @Mock private NotificationChannel smsChannel;
    @Mock private NotificationChannel emailChannel;
    @Mock private NotificationSettingService settingService;
    @Mock private NotificationRecipientResolver recipientResolver;
    @Mock private NotificationLogService notificationLogService;
    @Mock private SmsFallbackLimiter smsFallbackLimiter;

    private NotificationDispatcher dispatcher;

    private static final String USER_ID = "GD0001";
    private final NotificationContent content = NotificationContent.of("제목", "본문", Map.of());

    @BeforeEach
    void setUp() {
        lenient().when(fcmChannel.getType()).thenReturn(NotificationChannelType.FCM);
        lenient().when(smsChannel.getType()).thenReturn(NotificationChannelType.SMS);
        lenient().when(emailChannel.getType()).thenReturn(NotificationChannelType.EMAIL);
        lenient().when(fcmChannel.send(any(), any(), any())).thenReturn(ChannelResult.delivered());
        lenient().when(smsChannel.send(any(), any(), any())).thenReturn(ChannelResult.delivered());
        lenient().when(emailChannel.send(any(), any(), any())).thenReturn(ChannelResult.delivered());
        lenient().when(smsFallbackLimiter.tryAcquire(any(), any())).thenReturn(true);
        dispatcher = new NotificationDispatcher(List.of(fcmChannel, smsChannel, emailChannel), settingService,
                recipientResolver, notificationLogService, smsFallbackLimiter);
        lenient().when(recipientResolver.resolve(USER_ID))
                .thenReturn(new NotificationRecipient(USER_ID, "01012345678", "a@example.com", Status.ACTIVE));
    }

    @Test
    @DisplayName("이메일만 켠 사용자에게 허용 종류(연결 요청)는 이메일로 발송된다")
    void 허용종류는_이메일_발송() {
        given(settingService.enabledChannels(USER_ID)).willReturn(EnumSet.of(NotificationChannelType.EMAIL));

        NotificationLogResult result = dispatcher.dispatch(USER_ID, NotificationType.CONNECTION_REQUEST, content);

        verify(emailChannel).send(any(), any(), any());
        verify(fcmChannel, never()).send(any(), any(), any());
        assertThat(result).isEqualTo(NotificationLogResult.DELIVERED);
    }

    @Test
    @DisplayName("이메일을 켜도 반복 알림(복약 알림)은 이메일로 나가지 않는다")
    void 복약알림은_이메일_제외() {
        given(settingService.enabledChannels(USER_ID)).willReturn(EnumSet.of(NotificationChannelType.EMAIL));

        NotificationLogResult result = dispatcher.dispatch(USER_ID, NotificationType.MEDICATION_REMINDER, content);

        verify(emailChannel, never()).send(any(), any(), any());
        assertThat(result).isEqualTo(NotificationLogResult.NOT_SENT);
    }

    @Test
    @DisplayName("이메일을 켜도 이상감지는 이메일로 나가지 않는다")
    void 이상감지는_이메일_제외() {
        given(settingService.enabledChannels(USER_ID)).willReturn(EnumSet.of(NotificationChannelType.EMAIL));

        dispatcher.dispatch(USER_ID, NotificationType.ANOMALY_DETECTED, content);
        dispatcher.dispatch(USER_ID, NotificationType.ANOMALY_DETECTED_SELF, content);

        verify(emailChannel, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("이메일을 켠 사용자도 푸시가 미전달이면 이상감지 문자 대체가 그대로 나간다")
    void 이상감지_문자대체는_이메일에_막히지_않는다() {
        given(settingService.enabledChannels(USER_ID)).willReturn(EnumSet.of(NotificationChannelType.EMAIL));
        given(fcmChannel.send(any(), any(), any()))
                .willReturn(ChannelResult.failed(ChannelFailureReason.NO_DEVICE));

        NotificationLogResult result = dispatcher.dispatch(USER_ID, NotificationType.ANOMALY_DETECTED, content);

        verify(emailChannel, never()).send(any(), any(), any());
        verify(smsChannel).send(any(), any(), any());
        assertThat(result).isEqualTo(NotificationLogResult.SMS_FALLBACK);
    }

    @Test
    @DisplayName("SOS는 이메일과 무관하게 FCM 강제 + 문자 폴백만 쓴다")
    void SOS는_이메일_미사용() {
        given(fcmChannel.send(any(), any(), any()))
                .willReturn(ChannelResult.failed(ChannelFailureReason.NO_DEVICE));

        dispatcher.dispatch(USER_ID, NotificationType.WARD_SOS, content);

        verify(emailChannel, never()).send(any(), any(), any());
        verify(smsChannel).send(any(), any(), any());
    }

    @Test
    @DisplayName("이용 제한 계정에는 이메일도 보내지 않는다")
    void 이용제한_이메일_차단() {
        given(settingService.enabledChannels(USER_ID)).willReturn(EnumSet.of(NotificationChannelType.EMAIL));
        given(recipientResolver.resolve(USER_ID))
                .willReturn(new NotificationRecipient(USER_ID, null, "a@example.com", Status.RESTRICTED));

        NotificationLogResult result = dispatcher.dispatch(USER_ID, NotificationType.CONNECTION_REQUEST, content);

        verify(emailChannel, never()).send(any(), any(), any());
        assertThat(result).isEqualTo(NotificationLogResult.NOT_SENT);
    }

    @Test
    @DisplayName("이메일 채널 예외가 같이 켠 FCM 발송을 막지 않는다")
    void 이메일_실패_격리() {
        given(settingService.enabledChannels(USER_ID))
                .willReturn(EnumSet.of(NotificationChannelType.FCM, NotificationChannelType.EMAIL));
        given(emailChannel.send(any(), any(), any())).willThrow(new IllegalStateException("smtp down"));

        NotificationLogResult result = dispatcher.dispatch(USER_ID, NotificationType.CONNECTION_REQUEST, content);

        verify(fcmChannel).send(any(), any(), any());
        assertThat(result).isEqualTo(NotificationLogResult.DELIVERED);
    }
}

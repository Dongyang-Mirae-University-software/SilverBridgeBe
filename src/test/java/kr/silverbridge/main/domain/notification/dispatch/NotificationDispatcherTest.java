package kr.silverbridge.main.domain.notification.dispatch;

import kr.silverbridge.main.domain.notification.channel.NotificationChannel;
import kr.silverbridge.main.domain.notification.channel.NotificationChannelType;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.channel.NotificationRecipient;
import kr.silverbridge.main.domain.notification.service.NotificationSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * NotificationDispatcher 라우팅 단위 테스트.
 *
 * 검증: 켜진 채널만 발송 / 미구현 채널 무시 / 한 채널 실패가 다른 채널을 막지 않음(격리) / 활성 채널 없음.
 * (FCM/SMS 채널은 mock으로 대체하여 라우팅 결정만 검증한다.)
 */
@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

    @Mock private NotificationChannel fcmChannel;
    @Mock private NotificationChannel smsChannel;
    @Mock private NotificationSettingService settingService;
    @Mock private NotificationRecipientResolver recipientResolver;

    private NotificationDispatcher dispatcher;

    private static final String USER_ID = "WD0001";
    private final NotificationContent content =
            NotificationContent.of("제목", "본문", Map.of("type", "CONNECTION_REQUEST"));

    @BeforeEach
    void setUp() {
        lenient().when(fcmChannel.getType()).thenReturn(NotificationChannelType.FCM);
        lenient().when(smsChannel.getType()).thenReturn(NotificationChannelType.SMS);
        // KAKAO_ALIMTALK / EMAIL 구현체는 등록하지 않음(미구현 채널 시나리오 재현)
        dispatcher = new NotificationDispatcher(List.of(fcmChannel, smsChannel), settingService, recipientResolver);
        lenient().when(recipientResolver.resolve(USER_ID))
                .thenReturn(new NotificationRecipient(USER_ID, "01012345678", "a@b.com"));
    }

    @Test
    @DisplayName("켜진 채널(FCM)만 발송하고 꺼진 채널(SMS)은 발송하지 않는다")
    void 켜진채널만_발송() {
        given(settingService.enabledChannels(USER_ID)).willReturn(EnumSet.of(NotificationChannelType.FCM));

        dispatcher.dispatch(USER_ID, NotificationType.CONNECTION_REQUEST, content);

        verify(fcmChannel).send(any(), any());
        verify(smsChannel, never()).send(any(), any());
    }

    @Test
    @DisplayName("FCM+SMS 모두 켜지면 두 채널 모두 발송한다")
    void 두채널_모두발송() {
        given(settingService.enabledChannels(USER_ID))
                .willReturn(EnumSet.of(NotificationChannelType.FCM, NotificationChannelType.SMS));

        dispatcher.dispatch(USER_ID, NotificationType.CONNECTION_REQUEST, content);

        verify(fcmChannel).send(any(), any());
        verify(smsChannel).send(any(), any());
    }

    @Test
    @DisplayName("미구현 채널(KAKAO_ALIMTALK)만 켜져 있으면 아무것도 발송하지 않고 예외도 없다")
    void 미구현채널_무시() {
        given(settingService.enabledChannels(USER_ID))
                .willReturn(EnumSet.of(NotificationChannelType.KAKAO_ALIMTALK));

        dispatcher.dispatch(USER_ID, NotificationType.CONNECTION_REQUEST, content);

        verify(fcmChannel, never()).send(any(), any());
        verify(smsChannel, never()).send(any(), any());
    }

    @Test
    @DisplayName("한 채널(FCM) 발송이 예외를 던져도 다른 채널(SMS) 발송은 진행된다(격리)")
    void 채널실패_격리() {
        given(settingService.enabledChannels(USER_ID))
                .willReturn(EnumSet.of(NotificationChannelType.FCM, NotificationChannelType.SMS));
        doThrow(new RuntimeException("FCM 장애")).when(fcmChannel).send(any(), any());

        dispatcher.dispatch(USER_ID, NotificationType.CONNECTION_REQUEST, content);

        verify(fcmChannel).send(any(), any());
        verify(smsChannel).send(any(), any()); // FCM 실패에도 SMS는 발송됨
    }

    @Test
    @DisplayName("활성 채널이 없으면 수신자 조회조차 하지 않고 종료한다")
    void 활성채널없음_조기종료() {
        given(settingService.enabledChannels(USER_ID))
                .willReturn(EnumSet.noneOf(NotificationChannelType.class));

        dispatcher.dispatch(USER_ID, NotificationType.CONNECTION_REQUEST, content);

        verifyNoInteractions(recipientResolver);
        verify(fcmChannel, never()).send(any(), any());
        verify(smsChannel, never()).send(any(), any());
    }

    @Test
    @DisplayName("[분류 가드] 현재 디스패처를 경유하는 알림은 모두 '선택'(필수 아님)이다")
    void 현재_모든타입_선택() {
        // 필수 알림(SMS 인증번호)은 디스패처를 경유하지 않으므로 디스패처 경유 mandatory 타입은 아직 없다.
        // 향후 긴급 알림을 mandatory=true로 추가하면 디스패처가 설정을 무시하고 MANDATORY_CHANNELS로 강제 발송한다.
        // (이 가드는 분류 결정을 코드로 고정하고, mandatory 타입이 추가되면 의도적 검토를 유도한다.)
        boolean anyMandatory = Arrays.stream(NotificationType.values()).anyMatch(NotificationType::isMandatory);
        assertThat(anyMandatory).isFalse();
    }
}

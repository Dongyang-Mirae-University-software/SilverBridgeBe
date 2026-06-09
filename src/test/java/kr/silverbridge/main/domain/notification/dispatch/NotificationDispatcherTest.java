package kr.silverbridge.main.domain.notification.dispatch;

import kr.silverbridge.main.domain.notification.channel.NotificationChannel;
import kr.silverbridge.main.domain.notification.channel.NotificationChannelType;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.channel.NotificationRecipient;
import kr.silverbridge.main.domain.notification.service.FcmService;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
    @Mock private FcmService fcmService;

    private NotificationDispatcher dispatcher;

    private static final String USER_ID = "WD0001";
    private final NotificationContent content =
            NotificationContent.of("제목", "본문", Map.of("type", "CONNECTION_REQUEST"));

    @BeforeEach
    void setUp() {
        lenient().when(fcmChannel.getType()).thenReturn(NotificationChannelType.FCM);
        lenient().when(smsChannel.getType()).thenReturn(NotificationChannelType.SMS);
        // KAKAO_ALIMTALK / EMAIL 구현체는 등록하지 않음(미구현 채널 시나리오 재현)
        dispatcher = new NotificationDispatcher(List.of(fcmChannel, smsChannel), settingService, recipientResolver, fcmService);
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
    @DisplayName("필수 알림(WARD_SOS) + FCM 토큰 있음 → 설정 무시하고 FCM만 강제 발송(SMS 미발송)")
    void 필수알림_FCM토큰있음_FCM만() {
        // mandatory=true 타입은 settingService를 조회하지 않고 강제 발송한다. FCM 토큰이 있으면 푸시가 닿으므로
        // SMS 비용을 아끼고 FCM만 보낸다.
        when(fcmService.hasToken(USER_ID)).thenReturn(true);

        dispatcher.dispatch(USER_ID, NotificationType.WARD_SOS, content);

        verify(settingService, never()).enabledChannels(any()); // 설정 무시
        verify(fcmChannel).send(any(), any());
        verify(smsChannel, never()).send(any(), any());
    }

    @Test
    @DisplayName("필수 알림(WARD_SOS) + FCM 토큰 없음 → SMS로 폴백 발송(푸시가 닿지 않는 보호자 보강)")
    void 필수알림_FCM토큰없음_SMS폴백() {
        // 앱 미설치·로그아웃·토큰 만료로 FCM 토큰이 없으면 푸시가 닿지 않으므로 SMS를 폴백으로 발송한다.
        when(fcmService.hasToken(USER_ID)).thenReturn(false);

        dispatcher.dispatch(USER_ID, NotificationType.WARD_SOS, content);

        verify(settingService, never()).enabledChannels(any()); // 설정 무시
        verify(smsChannel).send(any(), any());
    }

    @Test
    @DisplayName("[분류 가드] 디스패처 경유 필수 타입은 WARD_SOS 뿐이고, 연결 알림은 모두 '선택'이다")
    void 분류_가드_필수타입_고정() {
        // 필수 알림(SMS 인증번호)은 디스패처를 경유하지 않으므로, 디스패처 경유 필수 타입은 긴급 SOS(WARD_SOS)가 유일하다.
        // 새 필수 타입을 추가하면 이 가드가 깨져 의도적 검토를 유도한다(분류 결정을 코드로 고정).
        Set<NotificationType> mandatory = Arrays.stream(NotificationType.values())
                .filter(NotificationType::isMandatory)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(mandatory).containsExactlyInAnyOrder(NotificationType.WARD_SOS);

        assertThat(NotificationType.CONNECTION_REQUEST.isMandatory()).isFalse();
        assertThat(NotificationType.CONNECTION_ACCEPTED.isMandatory()).isFalse();
        assertThat(NotificationType.CONNECTION_REFUSED.isMandatory()).isFalse();
        assertThat(NotificationType.CONNECTION_DISCONNECTED.isMandatory()).isFalse();
    }
}

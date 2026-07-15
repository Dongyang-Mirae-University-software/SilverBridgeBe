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
    @DisplayName("구현체 없는 채널(EMAIL)만 켜져 있으면 아무것도 발송하지 않고 예외도 없다")
    void 미구현채널_무시() {
        given(settingService.enabledChannels(USER_ID))
                .willReturn(EnumSet.of(NotificationChannelType.EMAIL));

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
    @DisplayName("필수 알림(WARD_SOS) + FCM 전달 성공 → 설정 무시하고 FCM만 발송(SMS 미발송)")
    void 필수알림_FCM전달성공_SMS미발송() {
        // mandatory=true 타입은 settingService를 조회하지 않고 강제 발송한다.
        // FCM이 실제로 전달됐으면(true) SMS 비용을 아끼고 폴백하지 않는다 (결과 기반, M-S2-1).
        when(fcmChannel.send(any(), any())).thenReturn(true);

        dispatcher.dispatch(USER_ID, NotificationType.WARD_SOS, content);

        verify(settingService, never()).enabledChannels(any()); // 설정 무시
        verify(fcmChannel).send(any(), any());
        verify(smsChannel, never()).send(any(), any());
    }

    @Test
    @DisplayName("필수 알림(WARD_SOS) + FCM 전달 실패(토큰 없음/전부 만료) → SMS로 폴백 발송")
    void 필수알림_FCM전달실패_SMS폴백() {
        // 토큰 부재·전 토큰 만료 등 "실제 전달 실패"(false)면 SMS를 폴백으로 발송한다 (M-S2-1).
        when(fcmChannel.send(any(), any())).thenReturn(false);

        dispatcher.dispatch(USER_ID, NotificationType.WARD_SOS, content);

        verify(settingService, never()).enabledChannels(any()); // 설정 무시
        verify(smsChannel).send(any(), any());
    }

    @Test
    @DisplayName("필수 알림(WARD_SOS) + FCM 발송 예외 → 예외도 전달 실패로 보고 SMS 폴백")
    void 필수알림_FCM예외_SMS폴백() {
        doThrow(new RuntimeException("FCM 장애")).when(fcmChannel).send(any(), any());

        dispatcher.dispatch(USER_ID, NotificationType.WARD_SOS, content);

        verify(smsChannel).send(any(), any());
    }

    @Test
    @DisplayName("이상감지: FCM을 꺼도 FCM은 발송되고, SMS가 꺼져 있으면 SMS는 발송하지 않는다")
    void 이상감지_FCM고정_SMS선택() {
        // FORCED_PUSH_PLUS_SETTINGS: FCM은 설정 무시(고정), 나머지 채널은 설정대로.
        given(settingService.enabledChannels(USER_ID)).willReturn(EnumSet.noneOf(NotificationChannelType.class));
        when(fcmChannel.send(any(), any())).thenReturn(true);

        dispatcher.dispatch(USER_ID, NotificationType.ANOMALY_DETECTED, content);

        verify(fcmChannel).send(any(), any());
        verify(smsChannel, never()).send(any(), any());
    }

    @Test
    @DisplayName("이상감지: SMS를 켠 사용자에게는 FCM + SMS 모두 발송한다")
    void 이상감지_SMS켜짐_추가발송() {
        given(settingService.enabledChannels(USER_ID)).willReturn(EnumSet.of(NotificationChannelType.SMS));
        when(fcmChannel.send(any(), any())).thenReturn(true);

        dispatcher.dispatch(USER_ID, NotificationType.ANOMALY_DETECTED, content);

        verify(fcmChannel).send(any(), any());
        verify(smsChannel).send(any(), any());
    }

    @Test
    @DisplayName("이상감지: FCM 전달에 실패해도 SMS로 폴백하지 않는다(문자는 사용자 선택)")
    void 이상감지_FCM미전달_SMS폴백없음() {
        // WARD_SOS와 결정적으로 다른 지점(D-2). 폴백하면 문자를 선택하지 않은 사용자에게 과금·발송이 발생한다.
        given(settingService.enabledChannels(USER_ID)).willReturn(EnumSet.noneOf(NotificationChannelType.class));
        when(fcmChannel.send(any(), any())).thenReturn(false);

        dispatcher.dispatch(USER_ID, NotificationType.ANOMALY_DETECTED, content);

        verify(smsChannel, never()).send(any(), any());
    }

    @Test
    @DisplayName("[정책 가드] 각 알림 타입의 채널 정책은 고정이다 — 바뀌면 의도적 검토를 유도한다")
    void 정책_가드() {
        // SMS 인증번호는 디스패처를 경유하지 않으므로, 강제 발송 정책을 갖는 타입은 아래 둘뿐이다.
        // 새 타입에 강제 정책을 붙이면 이 가드가 깨진다(분류 결정을 코드로 고정).
        Set<NotificationType> forced = Arrays.stream(NotificationType.values())
                .filter(type -> type.policy() != NotificationType.Policy.SETTINGS_ONLY)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(forced).containsExactlyInAnyOrder(
                NotificationType.WARD_SOS, NotificationType.ANOMALY_DETECTED);

        // SOS만 SMS 폴백을 갖는다. 이상감지는 FCM 고정 + 설정 채널(폴백 없음).
        assertThat(NotificationType.WARD_SOS.policy())
                .isEqualTo(NotificationType.Policy.FORCED_PUSH_WITH_SMS_FALLBACK);
        assertThat(NotificationType.ANOMALY_DETECTED.policy())
                .isEqualTo(NotificationType.Policy.FORCED_PUSH_PLUS_SETTINGS);
        assertThat(NotificationType.CONNECTION_REQUEST.policy())
                .isEqualTo(NotificationType.Policy.SETTINGS_ONLY);
        assertThat(NotificationType.INQUIRY_ANSWERED.policy())
                .isEqualTo(NotificationType.Policy.SETTINGS_ONLY);
    }
}

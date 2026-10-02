package kr.silverbridge.main.domain.notification.service;

import kr.silverbridge.main.domain.notification.channel.NotificationChannelType;
import kr.silverbridge.main.domain.notification.dto.NotificationSettingResponse;
import kr.silverbridge.main.domain.notification.dto.NotificationSettingUpdateRequest;
import kr.silverbridge.main.domain.notification.dto.NotificationSettingUpdateRequest.ChannelSettingUpdate;
import kr.silverbridge.main.domain.notification.entity.UserNotificationSetting;
import kr.silverbridge.main.domain.notification.repository.UserNotificationSettingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationSettingServiceTest {

    @Mock private UserNotificationSettingRepository repository;
    @InjectMocks private NotificationSettingService service;

    private static final String USER_ID = "WD0001";

    @Test
    @DisplayName("설정 행이 없으면 기본값(FCM만 ON)을 활성 채널로 반환한다")
    void enabledChannels_설정없음_기본FCM() {
        given(repository.findByUserId(USER_ID)).willReturn(List.of());

        assertThat(service.enabledChannels(USER_ID))
                .containsExactly(NotificationChannelType.FCM);
    }

    @Test
    @DisplayName("저장된 설정이 기본값을 덮어쓴다 — FCM OFF / SMS ON")
    void enabledChannels_저장값_우선() {
        given(repository.findByUserId(USER_ID)).willReturn(List.of(
                UserNotificationSetting.of(USER_ID, NotificationChannelType.FCM, false),
                UserNotificationSetting.of(USER_ID, NotificationChannelType.SMS, true)
        ));

        assertThat(service.enabledChannels(USER_ID))
                .containsExactly(NotificationChannelType.SMS);
    }

    @Test
    @DisplayName("getSettings는 기본값 병합 후 전체 채널을 반환한다(FCM만 ON)")
    void getSettings_전체채널_기본값병합() {
        given(repository.findByUserId(USER_ID)).willReturn(List.of());

        NotificationSettingResponse response = service.getSettings(USER_ID);

        assertThat(response.settings()).hasSize(NotificationChannelType.values().length);
        assertThat(response.settings())
                .filteredOn(s -> s.enabled())
                .extracting(NotificationSettingResponse.ChannelSetting::channelType)
                .containsExactly(NotificationChannelType.FCM);
    }

    @Test
    @DisplayName("updateSettings: 기존 행은 갱신, 없는 행은 충돌 무시 INSERT로 신규 저장(upsert)")
    void updateSettings_upsert() {
        // SMS는 기존 행 존재 → updateEnabled, KAKAO_ALIMTALK은 없음 → insertIfAbsent
        UserNotificationSetting existingSms =
                UserNotificationSetting.of(USER_ID, NotificationChannelType.SMS, false);
        given(repository.findByUserIdAndChannelType(USER_ID, NotificationChannelType.SMS))
                .willReturn(Optional.of(existingSms));
        given(repository.findByUserIdAndChannelType(USER_ID, NotificationChannelType.KAKAO_ALIMTALK))
                .willReturn(Optional.empty());
        given(repository.insertIfAbsent(USER_ID, "KAKAO_ALIMTALK", true)).willReturn(1);
        given(repository.findByUserId(USER_ID)).willReturn(List.of()); // 마지막 getSettings용

        NotificationSettingUpdateRequest request = new NotificationSettingUpdateRequest(List.of(
                new ChannelSettingUpdate(NotificationChannelType.SMS, true),
                new ChannelSettingUpdate(NotificationChannelType.KAKAO_ALIMTALK, true)
        ));

        service.updateSettings(USER_ID, request);

        assertThat(existingSms.isEnabled()).isTrue(); // 기존 행 갱신됨
        verify(repository).insertIfAbsent(USER_ID, "KAKAO_ALIMTALK", true);
        // SMS는 기존 행 갱신이라 INSERT 없음
        verify(repository, never()).insertIfAbsent(eq(USER_ID), eq("SMS"), anyBoolean());
        verify(repository, never()).save(any());
        // 새로 넣은 쪽은 재조회하지 않는다(조회 1회 = 최초 확인)
        verify(repository, times(1)).findByUserIdAndChannelType(USER_ID, NotificationChannelType.KAKAO_ALIMTALK);
    }

    @Test
    @DisplayName("updateSettings: 동시 최초 저장으로 다른 요청이 먼저 넣었으면(0건) 재조회해 이 요청 값으로 갱신 - 409 없음 (USER-G12)")
    void updateSettings_동시최초저장_재조회갱신() {
        // 처음 확인 땐 없었고, INSERT는 충돌로 0건, 다시 읽으면 상대가 넣은 행(enabled=false)이 있다
        UserNotificationSetting insertedByOther =
                UserNotificationSetting.of(USER_ID, NotificationChannelType.SMS, false);
        given(repository.findByUserIdAndChannelType(USER_ID, NotificationChannelType.SMS))
                .willReturn(Optional.empty(), Optional.of(insertedByOther));
        given(repository.insertIfAbsent(USER_ID, "SMS", true)).willReturn(0);
        given(repository.findByUserId(USER_ID)).willReturn(List.of(insertedByOther));

        NotificationSettingResponse response = service.updateSettings(USER_ID,
                new NotificationSettingUpdateRequest(List.of(
                        new ChannelSettingUpdate(NotificationChannelType.SMS, true))));

        assertThat(insertedByOther.isEnabled()).isTrue(); // 나중 요청 값으로 덮였다
        assertThat(response.settings())
                .filteredOn(s -> s.channelType() == NotificationChannelType.SMS)
                .extracting(NotificationSettingResponse.ChannelSetting::enabled)
                .containsExactly(true);
        verify(repository, never()).save(any());
    }
}

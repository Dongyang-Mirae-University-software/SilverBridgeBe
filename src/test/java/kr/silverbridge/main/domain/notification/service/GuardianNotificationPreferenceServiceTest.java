package kr.silverbridge.main.domain.notification.service;

import kr.silverbridge.main.domain.anomaly.dto.AnomalyReminderSettingResponse;
import kr.silverbridge.main.domain.anomaly.service.GuardianAnomalySettingService;
import kr.silverbridge.main.domain.notification.dto.GuardianNotificationTypeSettingRequest;
import kr.silverbridge.main.domain.notification.dto.GuardianNotificationTypeSettingResponse;
import kr.silverbridge.main.domain.notification.entity.GuardianNotificationPreference;
import kr.silverbridge.main.domain.notification.repository.GuardianNotificationPreferenceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GuardianNotificationPreferenceServiceTest {

    private static final String GUARDIAN = "GD0001";

    @Mock private GuardianNotificationPreferenceRepository repository;
    @Mock private GuardianAnomalySettingService anomalySettingService;
    @InjectMocks private GuardianNotificationPreferenceService service;

    @Test
    @DisplayName("행이 없으면 기본값 - 복약·재촉 ON, SOS·이상감지는 잠금(required)")
    void 기본값() {
        when(repository.findByGuardianId(GUARDIAN)).thenReturn(Optional.empty());
        when(anomalySettingService.getSetting(GUARDIAN)).thenReturn(new AnomalyReminderSettingResponse(true));

        GuardianNotificationTypeSettingResponse response = service.getSettings(GUARDIAN);

        assertThat(response.sos()).isEqualTo(new GuardianNotificationTypeSettingResponse.TypeSetting(true, true));
        assertThat(response.anomalyDetection()).isEqualTo(new GuardianNotificationTypeSettingResponse.TypeSetting(true, true));
        assertThat(response.anomalyReviewReminder().enabled()).isTrue();
        assertThat(response.anomalyReviewReminder().required()).isFalse();
        assertThat(response.medication().enabled()).isTrue();
        assertThat(response.medication().required()).isFalse();
    }

    @Test
    @DisplayName("요청이 전부 null이면 아무것도 저장하지 않는다 (null = 변경 안 함)")
    void 전부_null_미변경() {
        when(repository.findByGuardianId(GUARDIAN)).thenReturn(Optional.empty());
        when(anomalySettingService.getSetting(GUARDIAN)).thenReturn(new AnomalyReminderSettingResponse(true));

        service.updateSettings(GUARDIAN, new GuardianNotificationTypeSettingRequest(null, null));

        verify(repository, never()).insertIfAbsent(anyString(), anyBoolean());
        verify(anomalySettingService, never()).updateSetting(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("복약만 보내면 재촉 설정은 건드리지 않는다")
    void 복약만_변경() {
        when(repository.insertIfAbsent(GUARDIAN, false)).thenReturn(1);
        when(repository.findByGuardianId(GUARDIAN)).thenReturn(Optional.empty());
        when(anomalySettingService.getSetting(GUARDIAN)).thenReturn(new AnomalyReminderSettingResponse(true));

        service.updateSettings(GUARDIAN, new GuardianNotificationTypeSettingRequest(null, false));

        verify(repository).insertIfAbsent(GUARDIAN, false);
        verify(anomalySettingService, never()).updateSetting(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("동시 요청이 먼저 넣었으면(0건) 그 행을 읽어 값을 갱신한다")
    void 동시저장_재조회() {
        GuardianNotificationPreference existing = GuardianNotificationPreference.of(GUARDIAN, true);
        when(repository.insertIfAbsent(GUARDIAN, false)).thenReturn(0);
        when(repository.findByGuardianId(GUARDIAN)).thenReturn(Optional.of(existing));
        when(anomalySettingService.getSetting(GUARDIAN)).thenReturn(new AnomalyReminderSettingResponse(true));

        GuardianNotificationTypeSettingResponse response =
                service.updateSettings(GUARDIAN, new GuardianNotificationTypeSettingRequest(null, false));

        assertThat(existing.isMedicationEnabled()).isFalse();
        assertThat(response.medication().enabled()).isFalse();
    }

    @Test
    @DisplayName("재촉은 기존 보호자 재촉 설정에 위임한다")
    void 재촉_위임() {
        when(repository.findByGuardianId(GUARDIAN)).thenReturn(Optional.empty());
        when(anomalySettingService.getSetting(GUARDIAN)).thenReturn(new AnomalyReminderSettingResponse(false));

        GuardianNotificationTypeSettingResponse response =
                service.updateSettings(GUARDIAN, new GuardianNotificationTypeSettingRequest(false, null));

        verify(anomalySettingService).updateSetting(GUARDIAN, false);
        assertThat(response.anomalyReviewReminder().enabled()).isFalse();
    }

    @Test
    @DisplayName("계정 차원에서 복약을 끈 보호자만 골라낸다 (행 없음 = ON)")
    void 끈_보호자만() {
        GuardianNotificationPreference off = GuardianNotificationPreference.of("GD0002", false);
        GuardianNotificationPreference on = GuardianNotificationPreference.of("GD0003", true);
        when(repository.findByGuardianIdIn(List.of("GD0001", "GD0002", "GD0003"))).thenReturn(List.of(off, on));

        assertThat(service.medicationDisabledGuardians(List.of("GD0001", "GD0002", "GD0003")))
                .containsExactly("GD0002");
    }
}

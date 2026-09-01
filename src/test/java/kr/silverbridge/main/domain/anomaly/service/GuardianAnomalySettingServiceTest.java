package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.dto.AnomalyReminderSettingResponse;
import kr.silverbridge.main.domain.anomaly.entity.GuardianAnomalySetting;
import kr.silverbridge.main.domain.anomaly.repository.GuardianAnomalySettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 재촉 수신 설정.
 *
 * <p>이 설정이 있어야 하는 이유는 코드에 드러나지 않는다 - 보호자가 <b>이 알림만</b> 끌 수 없으면
 * 알림 피로로 앱 알림을 통째로 꺼버리고, 그때 SOS·화재 알림까지 함께 죽는다. 기본값 ON과
 * 부분 수정(null = 변경 안 함)을 테스트로 고정한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class GuardianAnomalySettingServiceTest {

    private static final String GUARDIAN_ID = "GD0001";

    @Mock private GuardianAnomalySettingRepository settingRepository;

    private GuardianAnomalySettingService service;

    @BeforeEach
    void setUp() {
        service = new GuardianAnomalySettingService(settingRepository);
    }

    @Test
    @DisplayName("저장된 행이 없으면 기본값 ON - 기존 보호자 백필이 필요 없다")
    void defaultsToEnabled() {
        when(settingRepository.findByGuardianId(GUARDIAN_ID)).thenReturn(Optional.empty());

        assertThat(service.getSetting(GUARDIAN_ID).reviewReminderEnabled()).isTrue();
    }

    @Test
    @DisplayName("끈 보호자는 저장된 값(OFF)을 그대로 돌려준다")
    void returnsStoredValue() {
        when(settingRepository.findByGuardianId(GUARDIAN_ID)).thenReturn(Optional.of(
                GuardianAnomalySetting.builder().guardianId(GUARDIAN_ID).reviewReminderEnabled(false).build()));

        assertThat(service.getSetting(GUARDIAN_ID).reviewReminderEnabled()).isFalse();
    }

    @Test
    @DisplayName("행이 없는 상태에서 끄면 행을 만들어 저장한다")
    void createsRowWhenTurningOff() {
        when(settingRepository.findByGuardianId(GUARDIAN_ID)).thenReturn(Optional.empty());
        when(settingRepository.save(any(GuardianAnomalySetting.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AnomalyReminderSettingResponse response = service.updateSetting(GUARDIAN_ID, false);

        assertThat(response.reviewReminderEnabled()).isFalse();
        verify(settingRepository).save(any(GuardianAnomalySetting.class));
    }

    @Test
    @DisplayName("null은 변경하지 않음 - 부분 수정 규약이라 저장 자체가 일어나지 않는다")
    void nullMeansNoChange() {
        when(settingRepository.findByGuardianId(GUARDIAN_ID)).thenReturn(Optional.of(
                GuardianAnomalySetting.builder().guardianId(GUARDIAN_ID).reviewReminderEnabled(false).build()));

        assertThat(service.updateSetting(GUARDIAN_ID, null).reviewReminderEnabled()).isFalse();
        verify(settingRepository, never()).save(any());
    }
}

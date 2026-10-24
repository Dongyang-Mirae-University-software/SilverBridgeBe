package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.medication.entity.MedicationSetting;
import kr.silverbridge.main.domain.medication.repository.MedicationSettingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * MedicationSettingService 조회 단위 테스트.
 *
 * <p>복용 시각 알림 ON/OFF 설정·저장 경로는 2026-10-24에 없앴다(알림은 늘 보낸다). 남은 것은 재알림 값 조회다.</p>
 */
@ExtendWith(MockitoExtension.class)
class MedicationSettingServiceTest {

    @Mock private MedicationSettingRepository repository;

    @InjectMocks private MedicationSettingService service;

    @Test
    @DisplayName("저장된 행은 재알림 값을, 행이 없는 피보호자는 기본값(재알림 켜짐)을 돌려준다 - 요청한 모두가 결과에 있다")
    void findPreferences_저장값과_기본값() {
        when(repository.findByUserIdIn(List.of("WD0001", "WD0002")))
                .thenReturn(List.of(MedicationSetting.of("WD0001", false, false)));

        Map<String, MedicationPreference> result = service.findPreferences(List.of("WD0001", "WD0002"));

        assertThat(result).containsEntry("WD0001", new MedicationPreference(false))
                .containsEntry("WD0002", MedicationPreference.DEFAULT);
    }

    @Test
    @DisplayName("빈 목록이면 저장소를 부르지 않는다")
    void findPreferences_빈목록() {
        assertThat(service.findPreferences(List.of())).isEmpty();
        verifyNoInteractions(repository);
    }
}

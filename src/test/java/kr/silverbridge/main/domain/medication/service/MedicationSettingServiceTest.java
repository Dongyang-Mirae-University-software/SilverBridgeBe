package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.medication.entity.MedicationSetting;
import kr.silverbridge.main.domain.medication.repository.MedicationSettingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * MedicationSettingService 설정 저장(upsert) 단위 테스트.
 *
 * <p>핵심 축 - <b>설정 행이 처음 만들어지는 순간 동시 요청이 와도 409가 나지 않는다</b>(MED-G13).
 * 실제 DB 경합은 {@code MedicationSettingConcurrencyIntegrationTest}가 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class MedicationSettingServiceTest {

    @Mock private MedicationSettingRepository repository;

    @InjectMocks private MedicationSettingService service;

    private static final String WARD_ID = "WD0001";

    @Test
    @DisplayName("행이 있으면 그 행만 갱신하고 INSERT는 시도하지 않는다")
    void 기존행_갱신() {
        MedicationSetting stored = MedicationSetting.of(WARD_ID, true, true);
        when(repository.findByUserId(WARD_ID)).thenReturn(Optional.of(stored));

        MedicationPreference applied = service.updatePreference(WARD_ID, false, null);

        assertThat(applied).isEqualTo(new MedicationPreference(false, true));
        verify(repository, never()).insertIfAbsent(any(), anyBoolean(), anyBoolean());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("행이 없으면 기본값으로 충돌 무시 INSERT 후 다시 읽어 갱신한다(find 후 save 아님)")
    void 미설정_생성후_갱신() {
        when(repository.findByUserId(WARD_ID))
                .thenReturn(Optional.empty(), Optional.of(MedicationSetting.of(WARD_ID, true, true)));
        when(repository.insertIfAbsent(WARD_ID, true, true)).thenReturn(1);

        MedicationPreference applied = service.updatePreference(WARD_ID, null, false);

        assertThat(applied).isEqualTo(new MedicationPreference(true, false));
        verify(repository).insertIfAbsent(WARD_ID, true, true);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("[MED-G13] 다른 보호자의 동시 요청이 먼저 만들었으면(INSERT 0건) 그 행에 이 요청 값을 적용한다 - 409 없음")
    void 동시_최초저장_충돌시_재조회_갱신() {
        // 먼저 커밋된 쪽이 알림을 껐다
        MedicationSetting createdByOther = MedicationSetting.of(WARD_ID, false, true);
        when(repository.findByUserId(WARD_ID)).thenReturn(Optional.empty(), Optional.of(createdByOther));
        when(repository.insertIfAbsent(any(), anyBoolean(), anyBoolean())).thenReturn(0);

        MedicationPreference applied = service.updatePreference(WARD_ID, null, false);

        // 이 요청이 보낸 재알림만 바뀌고, 먼저 저장된 알림 OFF는 기본값으로 되돌아가지 않는다
        assertThat(applied).isEqualTo(new MedicationPreference(false, false));
    }
}

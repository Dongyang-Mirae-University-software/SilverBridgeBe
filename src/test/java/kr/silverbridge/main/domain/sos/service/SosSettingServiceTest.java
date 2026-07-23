package kr.silverbridge.main.domain.sos.service;

import kr.silverbridge.main.domain.sos.dto.SosSettingResponse;
import kr.silverbridge.main.domain.sos.dto.SosSettingUpdateRequest;
import kr.silverbridge.main.domain.sos.entity.SosAction;
import kr.silverbridge.main.domain.sos.entity.SosSetting;
import kr.silverbridge.main.domain.sos.repository.SosSettingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SosSettingServiceTest {

    @Mock private SosSettingRepository repository;
    @InjectMocks private SosSettingService service;

    private static final String USER_ID = "WD0001";

    @Test
    @DisplayName("설정 행이 없으면 기본값(CALL_119_AND_NOTIFY)을 반환한다 — 백필 없이 기존 동작 보존")
    void getSetting_설정없음_기본값() {
        given(repository.findByUserId(USER_ID)).willReturn(Optional.empty());

        SosSettingResponse response = service.getSetting(USER_ID);

        assertThat(response.sosAction()).isEqualTo(SosAction.CALL_119_AND_NOTIFY);
    }

    @Test
    @DisplayName("저장된 설정이 기본값을 덮어쓴다")
    void getSetting_저장값_우선() {
        given(repository.findByUserId(USER_ID))
                .willReturn(Optional.of(SosSetting.of(USER_ID, SosAction.NOTIFY_GUARDIAN_FIRST)));

        SosSettingResponse response = service.getSetting(USER_ID);

        assertThat(response.sosAction()).isEqualTo(SosAction.NOTIFY_GUARDIAN_FIRST);
    }

    @Test
    @DisplayName("updateSetting: 기존 행이 있으면 갱신하고 새 행을 저장하지 않는다")
    void updateSetting_기존행_갱신() {
        SosSetting existing = SosSetting.of(USER_ID, SosAction.CALL_119_AND_NOTIFY);
        given(repository.findByUserId(USER_ID)).willReturn(Optional.of(existing));

        SosSettingResponse response =
                service.updateSetting(USER_ID, new SosSettingUpdateRequest(SosAction.CALL_119));

        assertThat(existing.getSosAction()).isEqualTo(SosAction.CALL_119);
        assertThat(response.sosAction()).isEqualTo(SosAction.CALL_119);
        verify(repository, never()).save(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("updateSetting: 기존 행이 없으면 신규 저장한다(upsert)")
    void updateSetting_신규_저장() {
        given(repository.findByUserId(USER_ID)).willReturn(Optional.empty());

        SosSettingResponse response =
                service.updateSetting(USER_ID, new SosSettingUpdateRequest(SosAction.NOTIFY_GUARDIAN_FIRST));

        verify(repository).save(ArgumentMatchers.argThat(s ->
                USER_ID.equals(s.getUserId()) && s.getSosAction() == SosAction.NOTIFY_GUARDIAN_FIRST));
        assertThat(response.sosAction()).isEqualTo(SosAction.NOTIFY_GUARDIAN_FIRST);
    }
}

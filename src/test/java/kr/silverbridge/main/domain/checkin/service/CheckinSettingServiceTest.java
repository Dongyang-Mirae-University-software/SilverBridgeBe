package kr.silverbridge.main.domain.checkin.service;

import kr.silverbridge.main.domain.checkin.config.CheckinProperties;
import kr.silverbridge.main.domain.checkin.dto.CheckinGuardianSettingDto;
import kr.silverbridge.main.domain.checkin.dto.CheckinWardSettingRequest;
import kr.silverbridge.main.domain.checkin.entity.CheckinSetting;
import kr.silverbridge.main.domain.checkin.repository.CheckinSettingRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckinSettingServiceTest {

    private static final String GUARDIAN = "gA1b2C";
    private static final String WARD = "wA1b2C";

    private final CheckinProperties properties = new CheckinProperties();
    private final CheckinSettingRepository repository = mock(CheckinSettingRepository.class);
    private final ConnectionService connectionService = mock(ConnectionService.class);
    private CheckinSettingService service;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setAiBaseUrl("http://127.0.0.1:1");
        properties.setApiKey("k");
        service = new CheckinSettingService(properties, repository, new CheckinGuardianAccess(connectionService));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("저장값이 없으면 표정 동의·안부 알림 모두 꺼짐")
    void defaults() {
        var r = service.getForWard(WARD);
        assertThat(r.faceConsent()).isFalse();
        assertThat(r.promptEnabled()).isFalse();
    }

    @Test
    @DisplayName("피보호자는 보낸 항목만 바꾸고, 안부 알림을 스스로 끌 수 있다")
    void wardPartialUpdate() {
        CheckinSetting setting = CheckinSetting.of(WARD, true);
        setting.changePromptEnabled(true, GUARDIAN);
        when(repository.findByWardId(WARD)).thenReturn(Optional.of(setting));

        var r = service.updateForWard(WARD, new CheckinWardSettingRequest(null, false));

        assertThat(r.faceConsent()).isTrue();
        assertThat(r.promptEnabled()).isFalse();
        assertThat(setting.getPromptChangedBy()).isEqualTo(WARD);
    }

    @Test
    @DisplayName("바꿀 항목이 없으면 400")
    void wardEmpty() {
        assertThatThrownBy(() -> service.updateForWard(WARD, new CheckinWardSettingRequest(null, null)))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CHECKIN_INVALID_SETTING);
    }

    @Test
    @DisplayName("보호자는 ACTIVE 연결일 때만 안부 알림을 켠다 - 표정 동의는 건드리지 못한다")
    void guardianToggle() {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
        when(repository.findByWardId(WARD)).thenReturn(Optional.empty());

        var r = service.updateForGuardian(GUARDIAN, WARD, new CheckinGuardianSettingDto(true));

        assertThat(r.promptEnabled()).isTrue();
        verify(repository).save(org.mockito.ArgumentMatchers.argThat(s ->
                s.isPromptEnabled() && !s.isFaceConsent() && GUARDIAN.equals(s.getPromptChangedBy())));
    }

    @Test
    @DisplayName("연결되지 않은 보호자는 403이고 저장하지 않는다")
    void guardianNotConnected() {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(false);
        assertThatThrownBy(() -> service.updateForGuardian(GUARDIAN, WARD, new CheckinGuardianSettingDto(true)))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CHECKIN_NOT_AUTHORIZED);
        verify(repository, never()).save(any());
    }
}

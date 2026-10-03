package kr.silverbridge.main.domain.anomaly.controller;

import kr.silverbridge.main.domain.anomaly.dto.AnomalyFeedbackRequest;
import kr.silverbridge.main.domain.anomaly.dto.AnomalyReminderSettingRequest;
import kr.silverbridge.main.domain.anomaly.dto.AnomalyReminderSettingResponse;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyVerdict;
import kr.silverbridge.main.domain.anomaly.service.GuardianAnomalyService;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipAccessService;
import kr.silverbridge.main.domain.anomaly.service.GuardianAnomalySettingService;
import kr.silverbridge.main.global.response.PageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * GuardianAnomalyController 권한 테스트.
 *
 * <p>이 컨트롤러는 경로 규칙 없이 <b>클래스 레벨 {@code @PreAuthorize}만이 게이트</b>라 이 테스트가 유일한
 * 회귀 방어다(2026-09-10 점검 M-6). 판정은 보호자만 한다 - 피보호자 본인·관리자가 1차 판정 API를 부를 수
 * 있으면 "보호자만 판정한다" 불변 규칙이 무너진다.</p>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        GuardianAnomalyControllerSecurityTest.MethodSecurityTestConfig.class,
        GuardianAnomalyController.class
})
class GuardianAnomalyControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private GuardianAnomalyService guardianAnomalyService;

    @MockitoBean
    private GuardianAnomalySettingService settingService;

    @MockitoBean
    private AnomalyClipAccessService clipAccessService;

    @Autowired
    private GuardianAnomalyController controller;

    private AnomalyFeedbackRequest feedback() {
        return new AnomalyFeedbackRequest(AnomalyVerdict.FALSE_ALARM);
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("GUARDIAN → 이력 조회·판정·재촉 설정 허용")
    void guardian_허용() {
        when(guardianAnomalyService.getHistory(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));
        when(settingService.getSetting(anyString())).thenReturn(new AnomalyReminderSettingResponse(true));
        when(settingService.updateSetting(anyString(), any())).thenReturn(new AnomalyReminderSettingResponse(false));

        assertThatNoException().isThrownBy(() -> controller.getHistory("GD0001", null, 0, 20));
        assertThatNoException().isThrownBy(() -> controller.submitFeedback("GD0001", 37L, feedback()));
        assertThatNoException().isThrownBy(() -> controller.getReminderSetting("GD0001"));
        assertThatNoException().isThrownBy(() ->
                controller.updateReminderSetting("GD0001", new AnomalyReminderSettingRequest(false)));
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("피보호자(WARD) → 403 (본인 상황이라도 1차 판정은 보호자만 한다)")
    void ward_거부() {
        assertThatThrownBy(() -> controller.getHistory("WD0001", null, 0, 20))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.submitFeedback("WD0001", 37L, feedback()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.getReminderSetting("WD0001"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("관리자(ADMIN) → 403 (관리자는 2차 정정만, 1차 판정 경로는 없다)")
    void admin_거부() {
        assertThatThrownBy(() -> controller.getHistory("AD0001", null, 0, 20))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.submitFeedback("AD0001", 37L, feedback()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("GUARDIAN → 영상 클립 목록·파일 허용 (연결 여부는 서비스가 검사)")
    void guardian_클립_허용() {
        when(clipAccessService.guardianClips(anyString(), any())).thenReturn(List.of());
        when(clipAccessService.guardianFile(anyString(), any()))
                .thenReturn(new AnomalyClipAccessService.ClipFile(101L, java.nio.file.Path.of("/x")));

        assertThatNoException().isThrownBy(() -> controller.getClips("GD0001", 37L));
        assertThatNoException().isThrownBy(() -> controller.getClipFile("GD0001", 101L));
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("피보호자(WARD) → 보호자용 영상 경로 403 (본인 열람은 /api/ward/anomaly/** 전용 경로)")
    void ward_클립_거부() {
        assertThatThrownBy(() -> controller.getClips("WD0001", 37L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.getClipFile("WD0001", 101L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("관리자(ADMIN) → 영상 경로 403 (관리자는 클립을 열람하지 않는다)")
    void admin_클립_거부() {
        assertThatThrownBy(() -> controller.getClips("AD0001", 37L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.getClipFile("AD0001", 101L)).isInstanceOf(AccessDeniedException.class);
    }
}

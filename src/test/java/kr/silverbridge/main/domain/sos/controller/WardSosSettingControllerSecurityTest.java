package kr.silverbridge.main.domain.sos.controller;

import kr.silverbridge.main.domain.sos.dto.SosSettingResponse;
import kr.silverbridge.main.domain.sos.dto.SosSettingUpdateRequest;
import kr.silverbridge.main.domain.sos.entity.SosAction;
import kr.silverbridge.main.domain.sos.service.SosSettingService;
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

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * WardSosSettingController 권한 테스트.
 *
 * SOS 동작 설정은 피보호자(WARD) 전용이다. 클래스 레벨 {@code @PreAuthorize("hasRole('WARD')")}를
 * 메서드 시큐리티(AOP)로 검증한다 — WARD는 허용, GUARDIAN은 403(AccessDeniedException).
 * ({@code WardSosControllerSecurityTest}와 동일한 방식.)
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        WardSosSettingControllerSecurityTest.MethodSecurityTestConfig.class,
        WardSosSettingController.class
})
class WardSosSettingControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private SosSettingService sosSettingService;

    @Autowired
    private WardSosSettingController controller;

    private static final SosSettingUpdateRequest REQUEST =
            new SosSettingUpdateRequest(SosAction.CALL_119);

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("WARD 역할 → 설정 조회 허용")
    void ward_조회_허용() {
        when(sosSettingService.getSetting(anyString()))
                .thenReturn(SosSettingResponse.of(SosAction.CALL_119_AND_NOTIFY));

        assertThatNoException().isThrownBy(() -> controller.getSetting("WD0001"));
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("WARD 역할 → 설정 변경 허용")
    void ward_변경_허용() {
        when(sosSettingService.updateSetting(anyString(), any()))
                .thenReturn(SosSettingResponse.of(SosAction.CALL_119));

        assertThatNoException().isThrownBy(() -> controller.updateSetting("WD0001", REQUEST));
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("WARD 아닌 역할(GUARDIAN) → 조회 403 (AccessDeniedException)")
    void 비WARD_조회_거부() {
        assertThatThrownBy(() -> controller.getSetting("GD0001"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("WARD 아닌 역할(GUARDIAN) → 변경 403 (AccessDeniedException)")
    void 비WARD_변경_거부() {
        assertThatThrownBy(() -> controller.updateSetting("GD0001", REQUEST))
                .isInstanceOf(AccessDeniedException.class);
    }
}

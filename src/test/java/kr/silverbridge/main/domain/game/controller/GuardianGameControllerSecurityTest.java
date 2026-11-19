package kr.silverbridge.main.domain.game.controller;

import kr.silverbridge.main.domain.game.service.GuardianGameService;
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
import static org.mockito.Mockito.verifyNoInteractions;

/** 게임 기록 중계 API 권한 - 보호자(GUARDIAN) 전용(WARD·ADMIN 403). */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        GuardianGameControllerSecurityTest.MethodSecurityTestConfig.class,
        GuardianGameController.class
})
class GuardianGameControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private GuardianGameService service;

    @Autowired
    private GuardianGameController controller;

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("GUARDIAN → 두 API 허용")
    void guardian() {
        assertThatNoException().isThrownBy(() -> controller.progress("GRD001", "aB3x9Z"));
        assertThatNoException().isThrownBy(() -> controller.activity("GRD001", "aB3x9Z", null));
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("WARD → 두 API 모두 403")
    void ward() {
        assertThatThrownBy(() -> controller.progress("WRD001", "aB3x9Z")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.activity("WRD001", "aB3x9Z", null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN → 두 API 모두 403")
    void admin() {
        assertThatThrownBy(() -> controller.progress("ADM001", "aB3x9Z")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.activity("ADM001", "aB3x9Z", null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }
}

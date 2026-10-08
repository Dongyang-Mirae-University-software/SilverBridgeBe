package kr.silverbridge.main.domain.dashboard.controller;

import kr.silverbridge.main.domain.dashboard.service.GuardianDashboardService;
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

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 대시보드는 보호자(GUARDIAN) 전용이다. guardianId 파라미터가 없음도 함께 고정한다. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        GuardianDashboardControllerSecurityTest.MethodSecurityTestConfig.class,
        GuardianDashboardController.class
})
class GuardianDashboardControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private GuardianDashboardService dashboardService;

    @Autowired
    private GuardianDashboardController controller;

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("GUARDIAN → 허용")
    void guardian_허용() {
        assertThatNoException().isThrownBy(() -> controller.getDashboard("GD0001", null));
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("WARD → 403")
    void ward_거부() {
        assertThatThrownBy(() -> controller.getDashboard("WD0001", null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN → 403")
    void admin_거부() {
        assertThatThrownBy(() -> controller.getDashboard("AD0001", null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("요청 파라미터에 guardianId가 없다 - 보호자는 토큰에서만")
    void guardianId_파라미터없음() {
        Method m = Arrays.stream(GuardianDashboardController.class.getDeclaredMethods())
                .filter(x -> x.getName().equals("getDashboard")).findFirst().orElseThrow();
        assertThat(Arrays.stream(m.getParameters()).map(p -> p.getName()))
                .doesNotContain("guardianIdParam");
        assertThat(Arrays.stream(m.getParameters())
                .filter(p -> p.isAnnotationPresent(org.springframework.web.bind.annotation.RequestParam.class)))
                .hasSize(1);
    }
}

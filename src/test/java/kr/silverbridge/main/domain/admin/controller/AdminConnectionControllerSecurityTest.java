package kr.silverbridge.main.domain.admin.controller;

import kr.silverbridge.main.domain.admin.dto.AdminForceConnectRequest;
import kr.silverbridge.main.domain.admin.service.AdminConnectionService;
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
 * AdminConnectionController 권한 테스트.
 *
 * <p>강제 연결은 피보호자의 수락(=동의) 없이 관계를 만드는 가장 민감한 관리자 조작이다.
 * 보호자가 이 경로를 부를 수 있으면 상대 동의 없이 스스로를 연결할 수 있으므로 반드시 ADMIN만이어야 한다
 * (2026-09-10 점검 M-6).</p>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        AdminConnectionControllerSecurityTest.MethodSecurityTestConfig.class,
        AdminConnectionController.class
})
class AdminConnectionControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private AdminConnectionService adminConnectionService;

    @Autowired
    private AdminConnectionController controller;

    private AdminForceConnectRequest request() {
        return new AdminForceConnectRequest("EE81BF", "C82D3E");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN → 강제 연결·해제 허용")
    void admin_허용() {
        when(adminConnectionService.forceConnect(any(), anyString())).thenReturn(null);

        assertThatNoException().isThrownBy(() -> controller.forceConnect(request(), "AD0001"));
        assertThatNoException().isThrownBy(() -> controller.forceDisconnect(42L, "AD0001"));
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("보호자(GUARDIAN) → 403 (동의 없는 연결을 스스로 만들 수 없다)")
    void guardian_거부() {
        assertThatThrownBy(() -> controller.forceConnect(request(), "GD0001"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.forceDisconnect(42L, "GD0001"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("피보호자(WARD) → 403")
    void ward_거부() {
        assertThatThrownBy(() -> controller.forceConnect(request(), "WD0001"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.forceDisconnect(42L, "WD0001"))
                .isInstanceOf(AccessDeniedException.class);
    }
}

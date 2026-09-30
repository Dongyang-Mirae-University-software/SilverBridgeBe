package kr.silverbridge.main.domain.connection.controller;

import kr.silverbridge.main.domain.connection.dto.ConnectionResponse;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * WardConnectionController 권한 테스트.
 *
 * <p>이 컨트롤러는 경로 규칙 없이 <b>클래스 레벨 {@code @PreAuthorize}만이 게이트</b>다.
 * 보호자가 이 경로를 부를 수 있으면 피보호자 대신 연결 요청을 수락·거절할 수 있으므로 WARD만이어야 한다.</p>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        WardConnectionControllerSecurityTest.MethodSecurityTestConfig.class,
        WardConnectionController.class
})
class WardConnectionControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private ConnectionService connectionService;

    @Autowired
    private WardConnectionController controller;

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("WARD → 보호자 목록 조회·요청 수락 허용")
    void ward_허용() {
        when(connectionService.getActiveGuardians(anyString())).thenReturn(List.<ConnectionResponse>of());

        assertThatNoException().isThrownBy(() -> controller.getActiveGuardians("WD0001"));
        assertThatNoException().isThrownBy(() -> controller.acceptConnection("WD0001", 37L));
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("보호자(GUARDIAN) → 403 (본인 대신 수락할 수 없다)")
    void guardian_거부() {
        assertThatThrownBy(() -> controller.getActiveGuardians("GD0001"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.acceptConnection("GD0001", 37L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("관리자(ADMIN) → 403")
    void admin_거부() {
        assertThatThrownBy(() -> controller.getActiveGuardians("AD0001"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.acceptConnection("AD0001", 37L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("미인증 → 401 (인증 정보 자체가 없음)")
    void 미인증_거부() {
        assertThatThrownBy(() -> controller.getActiveGuardians("WD0001"))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> controller.acceptConnection("WD0001", 37L))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }
}

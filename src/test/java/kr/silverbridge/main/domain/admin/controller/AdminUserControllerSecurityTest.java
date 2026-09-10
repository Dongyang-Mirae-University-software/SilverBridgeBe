package kr.silverbridge.main.domain.admin.controller;

import jakarta.servlet.http.HttpServletRequest;
import kr.silverbridge.main.domain.admin.dto.AdminUserCountsResponse;
import kr.silverbridge.main.domain.admin.dto.AdminUserUpdateRequest;
import kr.silverbridge.main.domain.admin.service.AdminUserService;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.response.PageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
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
 * AdminUserController 권한 테스트.
 *
 * <p>회원관리는 관리자(ADMIN) 전용이다. 경로 규칙({@code /api/admin/**})은 컨트롤러 밖에 있어 이 테스트로
 * 고정되지 않으므로, 클래스 레벨 {@code @PreAuthorize}를 메서드 시큐리티로 검증한다(2026-09-10 점검 M-6).</p>
 *
 * <p>특히 <b>보호자·피보호자가 남의 역할·상태를 바꾸거나 계정을 지울 수 없어야</b> 한다.</p>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        AdminUserControllerSecurityTest.MethodSecurityTestConfig.class,
        AdminUserController.class
})
class AdminUserControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private AdminUserService adminUserService;

    @Autowired
    private AdminUserController controller;

    private final HttpServletRequest httpRequest = Mockito.mock(HttpServletRequest.class);

    private AdminUserUpdateRequest updateRequest() {
        return new AdminUserUpdateRequest(null, Role.WARD, null, null);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN → 목록·건수·상세·수정·삭제 모두 허용")
    void admin_허용() {
        when(adminUserService.getUsers(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));
        when(adminUserService.getCounts()).thenReturn(new AdminUserCountsResponse(0, 0, 0, 0));

        assertThatNoException().isThrownBy(() -> controller.getUsers(null, null, null, null, 0, 20));
        assertThatNoException().isThrownBy(controller::getCounts);
        assertThatNoException().isThrownBy(() -> controller.getUser("EE81BF"));
        assertThatNoException().isThrownBy(() -> controller.updateUser("EE81BF", updateRequest(), "AD0001"));
        assertThatNoException().isThrownBy(() -> controller.forceDelete("EE81BF", "AD0001", httpRequest));
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("보호자(GUARDIAN) → 403")
    void guardian_거부() {
        assertThatThrownBy(() -> controller.getUsers(null, null, null, null, 0, 20))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.updateUser("EE81BF", updateRequest(), "GD0001"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.forceDelete("EE81BF", "GD0001", httpRequest))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("피보호자(WARD) → 403")
    void ward_거부() {
        assertThatThrownBy(() -> controller.getUser("EE81BF"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.updateUser("EE81BF", updateRequest(), "WD0001"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.forceDelete("EE81BF", "WD0001", httpRequest))
                .isInstanceOf(AccessDeniedException.class);
    }
}

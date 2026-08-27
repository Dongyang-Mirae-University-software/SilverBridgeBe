package kr.silverbridge.main.domain.sos.controller;

import kr.silverbridge.main.domain.sos.service.GuardianSosService;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * GuardianSosController 권한 테스트.
 *
 * <p>SOS 이력 조회는 보호자(GUARDIAN) 전용이다. 클래스 레벨 {@code @PreAuthorize("hasRole('GUARDIAN')")}를
 * 메서드 시큐리티(AOP)로 검증한다 - GUARDIAN은 허용, 피보호자(WARD)·관리자(ADMIN)는 403.
 * (연결 여부에 따른 IDOR 차단은 {@code GuardianSosServiceTest}가 담당한다.)</p>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        GuardianSosControllerSecurityTest.MethodSecurityTestConfig.class,
        GuardianSosController.class
})
class GuardianSosControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private GuardianSosService guardianSosService;

    @Autowired
    private GuardianSosController controller;

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("GUARDIAN 역할 → 이력 조회 허용")
    void guardian_이력조회_허용() {
        when(guardianSosService.getHistory(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));

        assertThatNoException().isThrownBy(() -> controller.getSosHistory("GD0001", null, 0, 20));
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("피보호자(WARD) → 이력 조회 403 (AccessDeniedException)")
    void ward_거부() {
        assertThatThrownBy(() -> controller.getSosHistory("WD0001", null, 0, 20))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("관리자(ADMIN) → 이력 조회 403 (AccessDeniedException)")
    void admin_거부() {
        assertThatThrownBy(() -> controller.getSosHistory("AD0001", null, 0, 20))
                .isInstanceOf(AccessDeniedException.class);
    }
}

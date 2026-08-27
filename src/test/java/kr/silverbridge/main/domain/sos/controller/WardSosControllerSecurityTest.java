package kr.silverbridge.main.domain.sos.controller;

import kr.silverbridge.main.domain.sos.dto.SosResponse;
import kr.silverbridge.main.domain.sos.service.SosService;
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

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * WardSosController 권한 테스트.
 *
 * SOS는 피보호자(WARD) 전용이다. 컨트롤러 클래스의 {@code @PreAuthorize("hasRole('WARD')")}를
 * 메서드 시큐리티(AOP)로 직접 검증한다 — WARD는 허용, WARD가 아닌 역할(GUARDIAN)은 403(AccessDeniedException).
 * (이 프로젝트는 MockMvc 컨트롤러 테스트 관례가 없어, 인가 규칙을 AOP 레벨에서 가볍게 고정한다.)
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        WardSosControllerSecurityTest.MethodSecurityTestConfig.class,
        WardSosController.class
})
class WardSosControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private SosService sosService;

    @Autowired
    private WardSosController controller;

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("WARD 역할 → SOS 호출 허용")
    void ward_허용() {
        when(sosService.trigger(anyString(), any(), any())).thenReturn(new SosResponse(1L, OffsetDateTime.now()));

        assertThatNoException().isThrownBy(() -> controller.triggerSos("WD0001", null));
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("WARD 아닌 역할(GUARDIAN) → 403 (AccessDeniedException)")
    void 비WARD_거부() {
        assertThatThrownBy(() -> controller.triggerSos("GD0001", null))
                .isInstanceOf(AccessDeniedException.class);
    }
}

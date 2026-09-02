package kr.silverbridge.main.domain.anomaly.controller;

import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalyReviewRequest;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.service.AdminAnomalyService;
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
 * AdminAnomalyController 권한 테스트.
 *
 * <p>이상감지 로그·정정은 관리자(ADMIN) 전용이다. 경로 규칙({@code /api/admin/**})은 컨트롤러 밖에
 * 있어 이 테스트로 고정되지 않으므로, 클래스 레벨 {@code @PreAuthorize}를 메서드 시큐리티로 검증한다.</p>
 *
 * <p>특히 <b>보호자가 정정 API를 부를 수 없어야</b> 한다 - 판정은 보호자, 정정은 관리자라는
 * 역할 분리가 무너지면 보호자가 스스로 확정해 버릴 수 있다.</p>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        AdminAnomalyControllerSecurityTest.MethodSecurityTestConfig.class,
        AdminAnomalyController.class
})
class AdminAnomalyControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private AdminAnomalyService adminAnomalyService;

    @Autowired
    private AdminAnomalyController controller;

    private AdminAnomalyReviewRequest request() {
        return new AdminAnomalyReviewRequest(AnomalyReviewStatus.FALSE_ALARM, "요리 연기");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN → 목록 조회 허용")
    void admin_목록_허용() {
        when(adminAnomalyService.getIncidents(any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));

        assertThatNoException().isThrownBy(() -> controller.getIncidents(null, null, 0, 20));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN → 정정 허용")
    void admin_정정_허용() {
        when(adminAnomalyService.resolve(anyString(), any(), any(), any())).thenReturn(null);

        assertThatNoException().isThrownBy(() -> controller.resolve("AD0001", 37L, request()));
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("보호자(GUARDIAN) → 403 (판정은 하되 정정은 못 한다)")
    void guardian_거부() {
        assertThatThrownBy(() -> controller.getIncidents(null, null, 0, 20))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.resolve("GD0001", 37L, request()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("피보호자(WARD) → 403")
    void ward_거부() {
        assertThatThrownBy(() -> controller.getIncidents(null, null, 0, 20))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.resolve("WD0001", 37L, request()))
                .isInstanceOf(AccessDeniedException.class);
    }
}

package kr.silverbridge.main.domain.admin.controller;

import kr.silverbridge.main.domain.admin.dto.AdminOperationDashboardResponse;
import kr.silverbridge.main.domain.admin.dto.AdminSafetyDashboardResponse;
import kr.silverbridge.main.domain.admin.service.AdminDashboardService;
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
import static org.mockito.Mockito.when;

/**
 * AdminDashboardController 권한 테스트.
 *
 * <p>대시보드는 관리자(ADMIN) 전용이다. {@code SecurityConfig}의 {@code /api/admin/**} 경로 규칙이
 * 1차 방어지만 그것은 컨트롤러 밖에 있어 이 테스트로 고정되지 않는다. 클래스 레벨
 * {@code @PreAuthorize("hasRole('ADMIN')")}를 메서드 시큐리티(AOP)로 검증한다 - 경로 패턴이 나중에
 * 바뀌어도 조용히 열리지 않게 하기 위함이다.</p>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        AdminDashboardControllerSecurityTest.MethodSecurityTestConfig.class,
        AdminDashboardController.class
})
class AdminDashboardControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private AdminDashboardService adminDashboardService;

    @Autowired
    private AdminDashboardController controller;

    private AdminSafetyDashboardResponse safety() {
        return new AdminSafetyDashboardResponse(
                false, 0, 0L, null,
                new AdminSafetyDashboardResponse.SafetyEvents(null, 0L, 0L, 0L),
                new AdminSafetyDashboardResponse.TodayAnomaly(
                        0L, List.of(), new AdminSafetyDashboardResponse.ReviewCount(0L, 0L, 0L, 0L)));
    }

    private AdminOperationDashboardResponse operation() {
        return new AdminOperationDashboardResponse(
                0L, 0L,
                new AdminOperationDashboardResponse.MemberComposition(0L, 0L),
                new AdminOperationDashboardResponse.Cameras(0L, 0L),
                0L,
                new AdminOperationDashboardResponse.UnansweredInquiries(0L, null),
                List.of(),
                new AdminOperationDashboardResponse.PendingItems(0L, 0L, 0L));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN 역할 → 안전 현황 조회 허용")
    void admin_안전현황_허용() {
        when(adminDashboardService.getSafetyDashboard()).thenReturn(safety());

        assertThatNoException().isThrownBy(() -> controller.getSafetyDashboard());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN 역할 → 운영 현황 조회 허용")
    void admin_운영현황_허용() {
        when(adminDashboardService.getOperationDashboard()).thenReturn(operation());

        assertThatNoException().isThrownBy(() -> controller.getOperationDashboard());
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("보호자(GUARDIAN) → 403 (AccessDeniedException)")
    void guardian_거부() {
        assertThatThrownBy(() -> controller.getSafetyDashboard())
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.getOperationDashboard())
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("피보호자(WARD) → 403 (AccessDeniedException)")
    void ward_거부() {
        assertThatThrownBy(() -> controller.getSafetyDashboard())
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.getOperationDashboard())
                .isInstanceOf(AccessDeniedException.class);
    }
}

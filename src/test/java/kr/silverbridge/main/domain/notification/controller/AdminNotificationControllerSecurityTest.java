package kr.silverbridge.main.domain.notification.controller;

import kr.silverbridge.main.domain.notification.service.AdminNotificationService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * AdminNotificationController 권한 테스트.
 *
 * <p>알림 이력에는 피보호자 이름·생활 상황(화재 감지·SOS)이 담긴 본문이 있어 관리자(ADMIN) 전용이다.
 * 경로 규칙({@code /api/admin/**})은 컨트롤러 밖에 있어 이 테스트로 고정되지 않으므로, 클래스 레벨
 * {@code @PreAuthorize}를 메서드 시큐리티로 검증한다(AdminAnomalyControllerSecurityTest와 같은 방식).</p>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        AdminNotificationControllerSecurityTest.MethodSecurityTestConfig.class,
        AdminNotificationController.class
})
class AdminNotificationControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private AdminNotificationService adminNotificationService;

    @Autowired
    private AdminNotificationController controller;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN → 목록·요약 조회 허용")
    void admin_허용() {
        when(adminNotificationService.getLogs(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));

        assertThatNoException().isThrownBy(() -> controller.getLogs(null, null, null, null, 0, 20));
        assertThatNoException().isThrownBy(() -> controller.getSummary(null, null, null));
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("보호자(GUARDIAN) → 목록·요약 모두 403")
    void guardian_거부() {
        assertThatThrownBy(() -> controller.getLogs(null, null, null, null, 0, 20))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.getSummary(null, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("피보호자(WARD) → 목록·요약 모두 403")
    void ward_거부() {
        assertThatThrownBy(() -> controller.getLogs(null, null, null, null, 0, 20))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.getSummary(null, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("알림 이력 API는 조회 전용 - 이력을 고치거나 지우는 쓰기 매핑이 없다")
    void 쓰기_매핑_없음() {
        List<String> writeMappings = Arrays.stream(AdminNotificationController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PatchMapping.class)
                        || method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PutMapping.class)
                        || method.isAnnotationPresent(DeleteMapping.class))
                .map(Method::getName)
                .toList();

        assertThat(writeMappings).isEmpty();
    }
}

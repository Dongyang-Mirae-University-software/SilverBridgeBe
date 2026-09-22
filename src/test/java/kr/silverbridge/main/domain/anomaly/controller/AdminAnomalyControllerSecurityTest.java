package kr.silverbridge.main.domain.anomaly.controller;

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
 * AdminAnomalyController 권한 테스트.
 *
 * <p>이상감지 로그는 관리자(ADMIN) 전용이다. 경로 규칙({@code /api/admin/**})은 컨트롤러 밖에
 * 있어 이 테스트로 고정되지 않으므로, 클래스 레벨 {@code @PreAuthorize}를 메서드 시큐리티로 검증한다.</p>
 *
 * <p>2026-09-21 관리자 정정을 폐지해 이 컨트롤러는 <b>조회 전용</b>이다. 판정을 바꾸는 엔드포인트가
 * 다시 생기지 않도록 매핑 종류까지 고정한다.</p>
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

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN → 목록 조회 허용")
    void admin_목록_허용() {
        when(adminAnomalyService.getIncidents(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));

        assertThatNoException().isThrownBy(() -> controller.getIncidents(null, null, null, null, null, 0, 20));
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("보호자(GUARDIAN) → 403")
    void guardian_거부() {
        assertThatThrownBy(() -> controller.getIncidents(null, null, null, null, null, 0, 20))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("피보호자(WARD) → 403")
    void ward_거부() {
        assertThatThrownBy(() -> controller.getIncidents(null, null, null, null, null, 0, 20))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN → 집계 조회 허용")
    void admin_집계_허용() {
        assertThatNoException().isThrownBy(() -> controller.getSummary(null, null, null));
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("보호자(GUARDIAN) → 집계도 403")
    void guardian_집계_거부() {
        assertThatThrownBy(() -> controller.getSummary(null, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("피보호자(WARD) → 집계도 403")
    void ward_집계_거부() {
        assertThatThrownBy(() -> controller.getSummary(null, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("관리자 이상감지 API는 조회 전용 - 판정을 바꾸는 쓰기 매핑이 없다")
    void 쓰기_매핑_없음() {
        List<String> writeMappings = Arrays.stream(AdminAnomalyController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PatchMapping.class)
                        || method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PutMapping.class)
                        || method.isAnnotationPresent(DeleteMapping.class))
                .map(Method::getName)
                .toList();

        assertThat(writeMappings).isEmpty();
    }
}

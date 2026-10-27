package kr.silverbridge.main.domain.admin.controller;

import kr.silverbridge.main.domain.admin.dto.AdminHospitalDepartmentRequest;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalStatusRequest;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalUpdateRequest;
import kr.silverbridge.main.domain.admin.service.AdminHospitalService;
import kr.silverbridge.main.global.response.PageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * AdminHospitalController 권한 테스트(2026-10-26). 예약 서비스 관리자 권한을 중계하므로 관리자 전용이다.
 * 경로 규칙({@code /api/admin/**}, 비로그인 401)은 컨트롤러 밖이라 클래스 레벨 {@code @PreAuthorize}를 메서드 시큐리티로 고정한다
 * (AdminCameraControllerSecurityTest와 같은 방식).
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        AdminHospitalControllerSecurityTest.MethodSecurityTestConfig.class,
        AdminHospitalController.class
})
class AdminHospitalControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private AdminHospitalService adminHospitalService;

    @Autowired
    private AdminHospitalController controller;

    private final AdminHospitalUpdateRequest update =
            new AdminHospitalUpdateRequest("이름", null, null, null, null, null, null, null);
    private final AdminHospitalStatusRequest status =
            new AdminHospitalStatusRequest(null, null, null, null, true, null);
    private final AdminHospitalDepartmentRequest department = new AdminHospitalDepartmentRequest("내과");

    private List<Executable> allEndpoints(String userId) {
        return List.of(
                () -> controller.getHospitals(userId, null, null, null, 0, 20),
                () -> controller.getSummary(userId),
                () -> controller.create(userId, null),
                () -> controller.update(userId, 1, update),
                () -> controller.delete(userId, 1),
                () -> controller.updateStatus(userId, 1, status),
                () -> controller.addDepartment(userId, 1, department),
                () -> controller.renameDepartment(userId, 1, 2, department),
                () -> controller.deleteDepartment(userId, 1, 2));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN → 9개 경로 모두 허용")
    void admin_허용() {
        when(adminHospitalService.getHospitals(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));

        allEndpoints("ADM001").forEach(call -> assertThatNoException().isThrownBy(call::execute));
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("보호자(GUARDIAN) → 9개 경로 모두 403")
    void guardian_거부() {
        allEndpoints("G00001").forEach(call ->
                assertThatThrownBy(call::execute).isInstanceOf(AccessDeniedException.class));
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("피보호자(WARD) → 9개 경로 모두 403")
    void ward_거부() {
        allEndpoints("W00001").forEach(call ->
                assertThatThrownBy(call::execute).isInstanceOf(AccessDeniedException.class));
    }

    @Test
    @DisplayName("경로는 /api/admin/** 아래다(SecurityConfig의 ADMIN 경로 규칙·비로그인 401 대상)")
    void under_admin_path() {
        String[] paths = AdminHospitalController.class.getAnnotation(RequestMapping.class).value();
        assertThat(paths).containsExactly("/api/admin/hospital");
    }
}

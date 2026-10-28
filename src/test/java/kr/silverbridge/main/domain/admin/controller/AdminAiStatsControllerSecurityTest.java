package kr.silverbridge.main.domain.admin.controller;

import kr.silverbridge.main.domain.admin.service.AdminAiChatStatsService;
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

/**
 * AdminAiStatsController 권한 테스트(2026-10-28). 경로 규칙({@code /api/admin/**})은 컨트롤러 밖이라
 * 클래스 레벨 {@code @PreAuthorize}를 메서드 시큐리티로 고정한다(비로그인 401은 경로 규칙이 담당 -
 * AdminCameraControllerSecurityTest와 같은 방식).
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        AdminAiStatsControllerSecurityTest.MethodSecurityTestConfig.class,
        AdminAiStatsController.class
})
class AdminAiStatsControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private AdminAiChatStatsService adminAiChatStatsService;

    @Autowired
    private AdminAiStatsController controller;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN → 챗 집계 조회 허용")
    void admin_허용() {
        assertThatNoException().isThrownBy(() -> controller.getChatStats("ADM001", "2026-10-01", "2026-10-31"));
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("보호자(GUARDIAN) → 403")
    void guardian_거부() {
        assertThatThrownBy(() -> controller.getChatStats("G00001", "2026-10-01", "2026-10-31"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("피보호자(WARD) → 403")
    void ward_거부() {
        assertThatThrownBy(() -> controller.getChatStats("W00001", "2026-10-01", "2026-10-31"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("AI 통계는 조회 전용 - 쓰기 매핑이 없다")
    void 쓰기_매핑_없음() {
        List<String> writeMappings = Arrays.stream(AdminAiStatsController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PatchMapping.class)
                        || method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PutMapping.class)
                        || method.isAnnotationPresent(DeleteMapping.class))
                .map(Method::getName)
                .toList();

        assertThat(writeMappings).isEmpty();
    }
}

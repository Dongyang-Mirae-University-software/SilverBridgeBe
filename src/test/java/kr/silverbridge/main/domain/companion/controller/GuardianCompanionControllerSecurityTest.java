package kr.silverbridge.main.domain.companion.controller;

import kr.silverbridge.main.domain.companion.service.CompanionGuardianService;
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
 * GuardianCompanionController 권한 테스트(2026-11-09 PR-B 점검 L-2). URL 규칙은 {@code authenticated}뿐이라
 * 보호자 전용은 클래스 레벨 {@code @PreAuthorize}가 지킨다 - 메서드 시큐리티로 고정한다(비로그인 401은 경로 규칙,
 * AdminAiStatsControllerSecurityTest와 같은 방식).
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        GuardianCompanionControllerSecurityTest.MethodSecurityTestConfig.class,
        GuardianCompanionController.class
})
class GuardianCompanionControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private CompanionGuardianService companionGuardianService;

    @Autowired
    private GuardianCompanionController controller;

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("보호자(GUARDIAN) → 오늘 안부 대화 조회 허용")
    void guardian_허용() {
        assertThatNoException().isThrownBy(() -> controller.today("G00001", "W00001"));
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("피보호자(WARD) → 403")
    void ward_거부() {
        assertThatThrownBy(() -> controller.today("W00001", "W00001")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("관리자(ADMIN) → 403 - 관리자는 집계 API(/api/admin/ai-stats/companion)만 본다")
    void admin_거부() {
        assertThatThrownBy(() -> controller.today("ADM001", "W00001")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("보호자 → 7일·날짜별·대화 보기 허용 (2026-11-11)")
    void guardian_새_조회_허용() {
        assertThatNoException().isThrownBy(() -> controller.week("G00001", "W00001"));
        assertThatNoException().isThrownBy(() -> controller.day("G00001", "W00001", "2026-11-11"));
        assertThatNoException().isThrownBy(() -> controller.session("G00001", "W00001", 1L));
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("피보호자 → 7일·날짜별·대화 보기 403 (2026-11-11)")
    void ward_새_조회_거부() {
        assertThatThrownBy(() -> controller.week("W00001", "W00001")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.day("W00001", "W00001", "2026-11-11")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.session("W00001", "W00001", 1L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("보호자 말벗 API는 조회 전용 - 쓰기 매핑이 없다")
    void 쓰기_매핑_없음() {
        List<String> writeMappings = Arrays.stream(GuardianCompanionController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PatchMapping.class)
                        || method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PutMapping.class)
                        || method.isAnnotationPresent(DeleteMapping.class))
                .map(Method::getName)
                .toList();

        assertThat(writeMappings).isEmpty();
    }
}

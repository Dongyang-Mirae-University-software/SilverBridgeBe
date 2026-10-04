package kr.silverbridge.main.domain.anomaly.controller;

import kr.silverbridge.main.domain.anomaly.service.AnomalyClipAccessService;
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

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * WardAnomalyClipController 권한 테스트 - 클래스 레벨 {@code @PreAuthorize}가 유일한 게이트다.
 * 피보호자 본인 열람 전용이라 보호자·관리자는 403, 쓰기(판정) 매핑은 없어야 한다.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        WardAnomalyClipControllerSecurityTest.MethodSecurityTestConfig.class,
        WardAnomalyClipController.class
})
class WardAnomalyClipControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private AnomalyClipAccessService clipAccessService;

    @Autowired
    private WardAnomalyClipController controller;

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("WARD → 목록·파일 허용 (본인·연결 여부는 서비스가 검사)")
    void ward_허용() {
        when(clipAccessService.wardClips(anyString(), any())).thenReturn(List.of());
        when(clipAccessService.wardFile(anyString(), any()))
                .thenReturn(new AnomalyClipAccessService.ClipFile(101L, Path.of("/x")));

        assertThatNoException().isThrownBy(() -> controller.getClips("WD0001", 37L));
        assertThatNoException().isThrownBy(() -> controller.getClipFile("WD0001", 101L));
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("GUARDIAN → 403 (보호자는 /api/guardian/anomaly/** 경로를 쓴다)")
    void guardian_거부() {
        assertThatThrownBy(() -> controller.getClips("GD0001", 37L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.getClipFile("GD0001", 101L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN → 403 (관리자는 클립을 열람하지 않는다)")
    void admin_거부() {
        assertThatThrownBy(() -> controller.getClips("AD0001", 37L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.getClipFile("AD0001", 101L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("조회 전용 - 피보호자용 판정·쓰기 매핑이 없다")
    void 쓰기_매핑_없음() {
        assertThat(Arrays.stream(WardAnomalyClipController.class.getDeclaredMethods()))
                .noneMatch(m -> m.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)
                        || m.isAnnotationPresent(org.springframework.web.bind.annotation.PutMapping.class)
                        || m.isAnnotationPresent(org.springframework.web.bind.annotation.PatchMapping.class)
                        || m.isAnnotationPresent(org.springframework.web.bind.annotation.DeleteMapping.class));
    }
}

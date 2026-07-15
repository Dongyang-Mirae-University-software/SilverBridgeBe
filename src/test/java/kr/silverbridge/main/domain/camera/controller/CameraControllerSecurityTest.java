package kr.silverbridge.main.domain.camera.controller;

import kr.silverbridge.main.domain.camera.dto.CameraUpdateRequest;
import kr.silverbridge.main.domain.camera.service.CameraService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 카메라 API 권한 테스트 (2026-07-14 점검 M-2 — 회귀 방지 장치가 없던 지점).
 *
 * 카메라 등록·수정·삭제는 피보호자(WARD) 전용이고, 연결된 피보호자 카메라 조회는 보호자(GUARDIAN) 전용이다.
 * 클래스 레벨 {@code @PreAuthorize}를 메서드 시큐리티(AOP)로 직접 검증한다(SOS 권한 테스트와 동일 패턴).
 * 소유권(IDOR) 검증 자체는 서비스 책임이므로 {@code CameraServiceTest}가 담당한다.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        CameraControllerSecurityTest.MethodSecurityTestConfig.class,
        WardCameraController.class,
        GuardianCameraController.class
})
class CameraControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private CameraService cameraService;

    @Autowired
    private WardCameraController wardController;

    @Autowired
    private GuardianCameraController guardianController;

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("WARD 역할 → 내 카메라 목록 조회 허용")
    void ward_카메라조회_허용() {
        when(cameraService.getMyCameras(anyString())).thenReturn(List.of());

        assertThatNoException().isThrownBy(() -> wardController.getMyCameras("WD0001"));
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("WARD 아닌 역할(GUARDIAN) → 카메라 삭제 거부(403)")
    void 비WARD_카메라삭제_거부() {
        assertThatThrownBy(() -> wardController.delete("GD0001", 1L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("WARD 아닌 역할(GUARDIAN) → 카메라 수정 거부(403)")
    void 비WARD_카메라수정_거부() {
        assertThatThrownBy(() -> wardController.update("GD0001", 1L, new CameraUpdateRequest("거실", true)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("GUARDIAN 역할 → 연결된 피보호자 카메라 조회 허용")
    void guardian_카메라조회_허용() {
        when(cameraService.getConnectedWardCameras(anyString())).thenReturn(List.of());

        assertThatNoException().isThrownBy(() -> guardianController.getConnectedWardCameras("GD0001"));
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("GUARDIAN 아닌 역할(WARD) → 보호자 카메라 목록 조회 거부(403)")
    void 비GUARDIAN_보호자조회_거부() {
        assertThatThrownBy(() -> guardianController.getConnectedWardCameras("WD0001"))
                .isInstanceOf(AccessDeniedException.class);
    }
}

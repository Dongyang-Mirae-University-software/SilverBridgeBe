package kr.silverbridge.main.domain.connection.controller;

import kr.silverbridge.main.domain.connection.dto.ConnectionRequestDto;
import kr.silverbridge.main.domain.connection.dto.ConnectionResponse;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.global.security.RateLimitService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GuardianConnectionController 권한 테스트.
 *
 * <p>이 컨트롤러는 경로 규칙 없이 <b>클래스 레벨 {@code @PreAuthorize}만이 게이트</b>다.
 * 피보호자가 이 경로를 부를 수 있으면 자기 자신을 임의로 연결·해제할 수 있으므로 GUARDIAN만이어야 한다.</p>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        GuardianConnectionControllerSecurityTest.MethodSecurityTestConfig.class,
        GuardianConnectionController.class
})
class GuardianConnectionControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private ConnectionService connectionService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @Autowired
    private GuardianConnectionController controller;

    private ConnectionRequestDto request() {
        ConnectionRequestDto dto = mock(ConnectionRequestDto.class);
        when(dto.getTargetId()).thenReturn("WD0001");
        when(dto.getRelation()).thenReturn("아들");
        return dto;
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("GUARDIAN → 피보호자 목록 조회·페어링 요청 허용")
    void guardian_허용() {
        when(connectionService.getMyWards(anyString(), any())).thenReturn(List.<ConnectionResponse>of());

        assertThatNoException().isThrownBy(() -> controller.getMyWards("GD0001", null));
        assertThatNoException().isThrownBy(() -> controller.requestConnection("GD0001", request()));
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("피보호자(WARD) → 403")
    void ward_거부() {
        assertThatThrownBy(() -> controller.getMyWards("WD0001", null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.requestConnection("WD0001", request()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("관리자(ADMIN) → 403")
    void admin_거부() {
        assertThatThrownBy(() -> controller.getMyWards("AD0001", null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.requestConnection("AD0001", request()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("미인증 → 401 (인증 정보 자체가 없음)")
    void 미인증_거부() {
        assertThatThrownBy(() -> controller.getMyWards("GD0001", null))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> controller.requestConnection("GD0001", request()))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }
}

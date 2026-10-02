package kr.silverbridge.main.domain.admin.controller;

import kr.silverbridge.main.domain.admin.dto.AdminAnnouncementDraftResponse;
import kr.silverbridge.main.domain.admin.dto.AnnouncementDraftCreateRequest;
import kr.silverbridge.main.domain.admin.service.AdminAnnouncementDraftService;
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

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AdminAnnouncementDraftController 권한 테스트.
 *
 * <p>임시저장 공지는 게시 전 초안이라 작성자 외 노출되면 안 되는 관리자 내부 자원이다. 경로 규칙
 * ({@code /api/admin/**})은 컨트롤러 밖에 있어 이 테스트로 고정되지 않으므로, 클래스 레벨
 * {@code @PreAuthorize}를 메서드 시큐리티로 검증한다(AdminAnomalyControllerSecurityTest와 같은 방식).</p>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        AdminAnnouncementDraftControllerSecurityTest.MethodSecurityTestConfig.class,
        AdminAnnouncementDraftController.class
})
class AdminAnnouncementDraftControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private AdminAnnouncementDraftService draftService;

    @Autowired
    private AdminAnnouncementDraftController controller;

    private AnnouncementDraftCreateRequest createRequest() {
        AnnouncementDraftCreateRequest dto = mock(AnnouncementDraftCreateRequest.class);
        when(dto.getTitle()).thenReturn("서비스 점검 안내");
        when(dto.getContent()).thenReturn("작성 중인 내용");
        return dto;
    }

    private AdminAnnouncementDraftResponse response() {
        return new AdminAnnouncementDraftResponse(1L, "AD0001", "관리자", "제목", "내용",
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN → 목록 조회·임시저장 생성 허용")
    void admin_허용() {
        when(draftService.getDrafts(0, 20)).thenReturn(List.of(response()));
        when(draftService.createDraft(any(), anyString())).thenReturn(response());

        assertThatNoException().isThrownBy(() -> controller.getDrafts(0, 20));
        assertThatNoException().isThrownBy(() -> controller.createDraft(createRequest(), "AD0001"));
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("보호자(GUARDIAN) → 403")
    void guardian_거부() {
        assertThatThrownBy(() -> controller.getDrafts(0, 20))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.createDraft(createRequest(), "GD0001"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("피보호자(WARD) → 403")
    void ward_거부() {
        assertThatThrownBy(() -> controller.getDrafts(0, 20))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.createDraft(createRequest(), "WD0001"))
                .isInstanceOf(AccessDeniedException.class);
    }
}

package kr.silverbridge.main.domain.inquiry.controller;

import kr.silverbridge.main.domain.inquiry.dto.AdminInquiryDetailResponse;
import kr.silverbridge.main.domain.inquiry.dto.AdminInquiryListResponse;
import kr.silverbridge.main.domain.inquiry.dto.InquiryAnswerRequest;
import kr.silverbridge.main.domain.inquiry.service.AdminInquiryService;
import kr.silverbridge.main.global.enums.InquiryCategory;
import kr.silverbridge.main.global.enums.InquiryStatus;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AdminInquiryController 권한 테스트.
 *
 * <p>문의 목록·상세에는 회원 이름·연락처가 섞인 본문이 있고 답변은 회원 화면에 그대로 노출되므로
 * ADMIN 전용이다. 경로 규칙({@code /api/admin/**})은 컨트롤러 밖에 있어 이 테스트로 고정되지 않으므로,
 * 클래스 레벨 {@code @PreAuthorize}를 메서드 시큐리티로 검증한다(AdminAnomalyControllerSecurityTest와 같은 방식).</p>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        AdminInquiryControllerSecurityTest.MethodSecurityTestConfig.class,
        AdminInquiryController.class
})
class AdminInquiryControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private AdminInquiryService adminInquiryService;

    @Autowired
    private AdminInquiryController controller;

    private InquiryAnswerRequest answerRequest() {
        InquiryAnswerRequest dto = mock(InquiryAnswerRequest.class);
        when(dto.getAnswer()).thenReturn("답변 내용");
        return dto;
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN → 목록 조회·답변 허용")
    void admin_허용() {
        when(adminInquiryService.getInquiries(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new AdminInquiryListResponse(0, 0, 0,
                        new PageResponse<>(List.of(), 0, 20, 0, 0, true)));
        when(adminInquiryService.answer(any(), any(), anyString()))
                .thenReturn(new AdminInquiryDetailResponse(37L, InquiryCategory.SERVICE, "제목", "내용",
                        "GD0001", "김보호", InquiryStatus.ANSWERED, "답변 내용", "관리자", null, null));

        assertThatNoException().isThrownBy(() -> controller.getInquiries(null, null, null, 0, 20));
        assertThatNoException().isThrownBy(() -> controller.answer(37L, answerRequest(), "AD0001"));
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("보호자(GUARDIAN) → 403 (본인 문의만 볼 수 있다)")
    void guardian_거부() {
        assertThatThrownBy(() -> controller.getInquiries(null, null, null, 0, 20))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.answer(37L, answerRequest(), "GD0001"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("피보호자(WARD) → 403")
    void ward_거부() {
        assertThatThrownBy(() -> controller.getInquiries(null, null, null, 0, 20))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.answer(37L, answerRequest(), "WD0001"))
                .isInstanceOf(AccessDeniedException.class);
    }
}

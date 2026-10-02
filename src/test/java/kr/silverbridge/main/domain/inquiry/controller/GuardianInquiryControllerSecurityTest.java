package kr.silverbridge.main.domain.inquiry.controller;

import kr.silverbridge.main.domain.inquiry.dto.InquiryCreateRequest;
import kr.silverbridge.main.domain.inquiry.dto.InquiryResponse;
import kr.silverbridge.main.domain.inquiry.service.InquiryService;
import kr.silverbridge.main.global.enums.InquiryCategory;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.security.RateLimitService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GuardianInquiryController 권한 테스트.
 *
 * <p>이 컨트롤러는 경로 규칙 없이 <b>클래스 레벨 {@code @PreAuthorize}만이 게이트</b>다.
 * 문의 작성·본인 문의 조회는 보호자만 - 피보호자·관리자가 부를 수 있으면 다른 회원 이름으로
 * 문의가 쌓이거나 관리자가 셀프 문의를 만들 수 있다.</p>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        GuardianInquiryControllerSecurityTest.MethodSecurityTestConfig.class,
        GuardianInquiryController.class
})
class GuardianInquiryControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private InquiryService inquiryService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @Autowired
    private GuardianInquiryController controller;

    private InquiryCreateRequest request() {
        InquiryCreateRequest dto = mock(InquiryCreateRequest.class);
        when(dto.getCategory()).thenReturn(InquiryCategory.SERVICE);
        when(dto.getTitle()).thenReturn("문의 제목");
        when(dto.getContent()).thenReturn("문의 내용");
        return dto;
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("GUARDIAN → 문의 목록 조회·작성 허용")
    void guardian_허용() {
        when(inquiryService.getMyInquiries(anyString(), anyInt(), anyInt())).thenReturn(List.<InquiryResponse>of());

        assertThatNoException().isThrownBy(() -> controller.getMyInquiries("GD0001", 0, 20));
        assertThatNoException().isThrownBy(() -> controller.create("GD0001", request()));
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("피보호자(WARD) → 403")
    void ward_거부() {
        assertThatThrownBy(() -> controller.getMyInquiries("WD0001", 0, 20))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.create("WD0001", request()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("관리자(ADMIN) → 403 (관리자는 답변만, 문의 작성 경로는 없다)")
    void admin_거부() {
        assertThatThrownBy(() -> controller.getMyInquiries("AD0001", 0, 20))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.create("AD0001", request()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("문의 작성은 사용자 기준 분당 5회·시간당 30회로 제한한다 - 초과 시 429, 작성은 수행하지 않는다 (ADMIN-G26)")
    void 문의_작성_속도_제한() {
        doThrow(new CustomException(ErrorCode.TOO_MANY_REQUESTS))
                .when(rateLimitService).check("inquiry-create", "GD0001", 5, 30);

        assertThatThrownBy(() -> controller.create("GD0001", request()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOO_MANY_REQUESTS);

        verify(inquiryService, never()).create(anyString(), org.mockito.ArgumentMatchers.any());
    }
}

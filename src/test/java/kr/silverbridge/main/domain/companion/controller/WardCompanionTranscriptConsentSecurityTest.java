package kr.silverbridge.main.domain.companion.controller;

import kr.silverbridge.main.domain.companion.dto.CompanionDtos.TranscriptConsentRequest;
import kr.silverbridge.main.domain.companion.service.CompanionService;
import kr.silverbridge.main.domain.companion.service.CompanionTranscriptService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
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

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 대화 내용 저장 동의 API는 피보호자 본인만(클래스 레벨 {@code hasRole('WARD')}, 2026-11-11). */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        WardCompanionTranscriptConsentSecurityTest.MethodSecurityTestConfig.class,
        WardCompanionController.class
})
class WardCompanionTranscriptConsentSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private CompanionService companionService;

    @MockitoBean
    private CompanionTranscriptService transcriptService;

    @Autowired
    private WardCompanionController controller;

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("피보호자 → 동의 조회·변경 허용, consent 없는 본문은 400")
    void ward_허용() {
        assertThatNoException().isThrownBy(() -> controller.getTranscriptConsent("W00001"));
        assertThatNoException().isThrownBy(() ->
                controller.setTranscriptConsent("W00001", new TranscriptConsentRequest(false)));
        assertThatThrownBy(() -> controller.setTranscriptConsent("W00001", new TranscriptConsentRequest(null)))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("보호자 → 403, 동의를 바꾸지 못한다")
    void guardian_거부() {
        assertThatThrownBy(() -> controller.setTranscriptConsent("G00001", new TranscriptConsentRequest(true)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.getTranscriptConsent("G00001")).isInstanceOf(AccessDeniedException.class);
        verify(transcriptService, never()).setConsent(anyString(), anyBoolean());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("관리자 → 403")
    void admin_거부() {
        assertThatThrownBy(() -> controller.setTranscriptConsent("ADM001", new TranscriptConsentRequest(true)))
                .isInstanceOf(AccessDeniedException.class);
    }
}

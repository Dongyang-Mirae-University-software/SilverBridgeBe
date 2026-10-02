package kr.silverbridge.main.domain.auth.controller;

import kr.silverbridge.main.domain.auth.dto.PasswordResetEmailVerifyRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetSmsVerifyRequest;
import kr.silverbridge.main.domain.auth.dto.SmsVerifyRequest;
import kr.silverbridge.main.domain.auth.service.PasswordResetService;
import kr.silverbridge.main.domain.auth.service.SmsService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.security.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 인증번호 확인 3종의 IP 속도 제한 (AUTH-G10) - 서비스(코드 비교) 전에 걸고, 초과 시 비교하지 않는다. */
@ExtendWith(MockitoExtension.class)
class VerifyRateLimitTest {

    @Mock private SmsService smsService;
    @Mock private PasswordResetService passwordResetService;
    @Mock private RateLimitService rateLimitService;

    private SmsController smsController;
    private FindPasswordController findPasswordController;
    private MockHttpServletRequest http;

    @BeforeEach
    void setUp() {
        smsController = new SmsController(smsService, rateLimitService);
        findPasswordController = new FindPasswordController(passwordResetService, rateLimitService);
        http = new MockHttpServletRequest();
        http.addHeader("X-Real-IP", "1.2.3.4");
    }

    @Test
    @DisplayName("가입 SMS 확인: IP 이중 윈도우(분 10/시 60) 검사 후 코드 확인")
    void signupSmsVerifyIsRateLimited() {
        SmsVerifyRequest req = mock(SmsVerifyRequest.class);

        smsController.verify(req, http);

        InOrder order = inOrder(rateLimitService, smsService);
        order.verify(rateLimitService).check("signup-sms-verify", "1.2.3.4", 10, 60);
        order.verify(smsService).verifyCode(req);
    }

    @Test
    @DisplayName("비밀번호 찾기 이메일·SMS 확인도 각자 키로 같은 한도를 건다")
    void passwordResetVerifiesAreRateLimited() {
        findPasswordController.verifyEmail(mock(PasswordResetEmailVerifyRequest.class), http);
        findPasswordController.verifySms(mock(PasswordResetSmsVerifyRequest.class), http);

        verify(rateLimitService).check("pw-reset-email-verify", "1.2.3.4", 10, 60);
        verify(rateLimitService).check("pw-reset-sms-verify", "1.2.3.4", 10, 60);
    }

    @Test
    @DisplayName("한도 초과(429)면 코드 비교 자체를 하지 않는다")
    void overLimitSkipsCompare() {
        doThrow(new CustomException(ErrorCode.TOO_MANY_REQUESTS))
                .when(rateLimitService).check("pw-reset-sms-verify", "1.2.3.4", 10, 60);

        assertThatThrownBy(() -> findPasswordController.verifySms(mock(PasswordResetSmsVerifyRequest.class), http))
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        verify(passwordResetService, never()).verifySmsCode(any());
    }
}

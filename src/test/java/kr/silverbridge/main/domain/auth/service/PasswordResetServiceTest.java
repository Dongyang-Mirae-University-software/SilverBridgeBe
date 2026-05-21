package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.dto.PasswordResetConfirmRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetRequest;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.event.PasswordChangedEvent;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.util.VerificationCodeValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = Strictness.LENIENT)
class PasswordResetServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AccessLogService accessLogService;
    @Mock private JavaMailSender mailSender;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private SmsVerificationService smsVerificationService;
    @Mock private VerificationCodeValidator verificationCodeValidator;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private PasswordResetService passwordResetService;

    private static final String EMAIL = "user@example.com";
    private static final String IP = "127.0.0.1";
    private static final String AGENT = "TestAgent/1.0";

    // ─── requestReset (이메일) — 가입 여부 노출 방지 (always-200) ──────────────

    @Test
    @DisplayName("미가입 이메일로 재설정 요청 → 조용히 종료, 이메일 미발송 (가입 여부 노출 방지)")
    void requestReset_미가입_이메일미발송() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        passwordResetService.requestReset(passwordResetRequest());

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("카카오 사용자 이메일로 재설정 요청 → 조용히 종료, 이메일 미발송")
    void requestReset_카카오사용자_이메일미발송() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(socialUser()));

        passwordResetService.requestReset(passwordResetRequest());

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("가입된 LOCAL 사용자 → 인증코드 이메일 발송 + Redis 저장")
    void requestReset_정상_이메일발송및저장() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(localUser("encodedCurrent")));

        passwordResetService.requestReset(passwordResetRequest());

        verify(mailSender).send(any(SimpleMailMessage.class));
        verify(valueOperations).set(anyString(), anyString(), anyLong(), any());
    }

    // ─── confirmReset — A-M1 enumeration 차단 ─────────────────────────────────

    @Test
    @DisplayName("confirmReset은 코드 검증을 사용자 조회보다 먼저 수행한다 — 코드 무효 시 사용자 조회 안 함 (A-M1 enumeration 차단)")
    void confirmReset_코드검증_사용자조회보다_선행() {
        PasswordResetConfirmRequest req = confirmRequest(EMAIL, null, "Brand-New1!");
        doThrow(new CustomException(ErrorCode.EXPIRED_SMS_CODE))
                .when(verificationCodeValidator).verify(anyString(), anyString(), anyString(), anyLong(), anyInt());

        CustomException ex = assertThrows(CustomException.class,
                () -> passwordResetService.confirmReset(req, IP, AGENT));

        // 미가입/가입 모두 코드 단계에서 동일하게 막혀 가입 여부가 새지 않는다
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.EXPIRED_SMS_CODE);
        verify(userRepository, never()).findByEmail(anyString());
        verify(userRepository, never()).findByPhone(anyString());
    }

    @Test
    @DisplayName("email/phone 둘 다 지정 → INVALID_INPUT (코드 검증 이전 단계)")
    void confirmReset_둘다지정_INVALID_INPUT() {
        PasswordResetConfirmRequest req = confirmRequest(EMAIL, "01012345678", "Brand-New1!");

        CustomException ex = assertThrows(CustomException.class,
                () -> passwordResetService.confirmReset(req, IP, AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
        verify(verificationCodeValidator, never()).verify(anyString(), anyString(), anyString(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("유효한 코드 + 새 비밀번호 → 비밀번호 변경 + PasswordChangedEvent 발행")
    void confirmReset_정상_비밀번호변경및이벤트() {
        PasswordResetConfirmRequest req = confirmRequest(EMAIL, null, "Brand-New1!");
        User user = localUser("encodedCurrent");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Brand-New1!", "encodedCurrent")).thenReturn(false);
        when(passwordEncoder.encode("Brand-New1!")).thenReturn("encodedNew");

        passwordResetService.confirmReset(req, IP, AGENT);

        assertThat(user.getPassword()).isEqualTo("encodedNew");
        verify(eventPublisher).publishEvent(any(PasswordChangedEvent.class));
        verify(accessLogService).log(eq(user.getId()),
                eq(kr.silverbridge.main.global.enums.AccessAction.PASSWORD_RESET), eq(IP), eq(AGENT));
    }

    @Test
    @DisplayName("현재 비밀번호와 동일한 새 비밀번호 → SAME_AS_CURRENT_PASSWORD")
    void confirmReset_현재와동일_SAME_AS_CURRENT_PASSWORD() {
        PasswordResetConfirmRequest req = confirmRequest(EMAIL, null, "SamePass1!");
        User user = localUser("encodedCurrent");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("SamePass1!", "encodedCurrent")).thenReturn(true);

        CustomException ex = assertThrows(CustomException.class,
                () -> passwordResetService.confirmReset(req, IP, AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SAME_AS_CURRENT_PASSWORD);
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────

    private PasswordResetRequest passwordResetRequest() {
        PasswordResetRequest req = mock(PasswordResetRequest.class);
        when(req.getEmail()).thenReturn(EMAIL);
        return req;
    }

    private PasswordResetConfirmRequest confirmRequest(String email, String phone, String newPassword) {
        PasswordResetConfirmRequest req = mock(PasswordResetConfirmRequest.class);
        when(req.getEmail()).thenReturn(email);
        when(req.getPhone()).thenReturn(phone);
        when(req.getCode()).thenReturn("123456");
        when(req.getNewPassword()).thenReturn(newPassword);
        return req;
    }

    private User localUser(String encodedPassword) {
        return User.builder()
                .id("usr123")
                .email(EMAIL)
                .password(encodedPassword)
                .name("테스트")
                .role(Role.WARD)
                .status(Status.ACTIVE)
                .provider(Provider.LOCAL)
                .build();
    }

    private User socialUser() {
        return User.builder()
                .id("kak123")
                .email(EMAIL)
                .name("카카오")
                .role(Role.WARD)
                .status(Status.ACTIVE)
                .provider(Provider.KAKAO)
                .providerId("999")
                .build();
    }
}

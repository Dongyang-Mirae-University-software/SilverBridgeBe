package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.dto.PasswordResetConfirmRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetSmsSendRequest;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.event.PasswordChangedEvent;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.util.RedisCounter;
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

import java.util.List;
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
    @Mock private RedisCounter redisCounter;

    @InjectMocks private PasswordResetService passwordResetService;

    private static final String EMAIL = "user@example.com";
    private static final String NAME = "테스트";
    private static final String PHONE = "01012345678";
    private static final String IP = "127.0.0.1";
    private static final String AGENT = "TestAgent/1.0";

    // ─── requestReset (이메일) — 시니어 친화 명시적 응답 (2026-05-23 정책 변경) ──────────

    @Test
    @DisplayName("미가입 이메일로 재설정 요청 → EMAIL_ACCOUNT_NOT_FOUND(404), 이메일 미발송")
    void requestReset_미가입_404_이메일미발송() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class,
                () -> passwordResetService.requestReset(passwordResetRequest(), IP));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.EMAIL_ACCOUNT_NOT_FOUND);
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("카카오 사용자 이메일로 재설정 요청 → SOCIAL_USER_NO_PASSWORD(400), 이메일 미발송")
    void requestReset_카카오사용자_400_이메일미발송() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(socialUser()));

        CustomException ex = assertThrows(CustomException.class,
                () -> passwordResetService.requestReset(passwordResetRequest(), IP));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SOCIAL_USER_NO_PASSWORD);
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("가입된 LOCAL 사용자 → 인증코드 이메일 발송 + Redis 저장")
    void requestReset_정상_이메일발송및저장() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(localUser("encodedCurrent")));
        when(redisCounter.incrementWithTtl(anyString(), anyLong())).thenReturn(1L); // per-email 상한 이내

        passwordResetService.requestReset(passwordResetRequest(), IP);

        verify(mailSender).send(any(SimpleMailMessage.class));
        verify(valueOperations).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("per-email 발송 상한 초과 → TOO_MANY_REQUESTS(429), 이메일 미발송 (#2 메일 폭탄 방어)")
    void requestReset_perEmail상한초과_429() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(localUser("encodedCurrent")));
        when(redisCounter.incrementWithTtl(anyString(), anyLong())).thenReturn(11L); // 상한(10) 초과

        CustomException ex = assertThrows(CustomException.class,
                () -> passwordResetService.requestReset(passwordResetRequest(), IP));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    // ─── requestResetBySms — 시니어 친화 명시적 응답 (2026-05-23 정책 변경) ──────────────

    @Test
    @DisplayName("이름+전화번호 미일치 → USER_NOT_FOUND(404), SMS 미발송 (#4 비용 보호)")
    void requestResetBySms_미일치_404_SMS미발송() {
        when(userRepository.findAllByNameAndPhone(NAME, PHONE)).thenReturn(List.of());

        CustomException ex = assertThrows(CustomException.class,
                () -> passwordResetService.requestResetBySms(smsSendRequest(), IP));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
        verify(smsVerificationService, never()).sendCode(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("카카오 계정만 매칭 → SOCIAL_USER_NO_PASSWORD(400), SMS 미발송")
    void requestResetBySms_카카오만매칭_400_SMS미발송() {
        when(userRepository.findAllByNameAndPhone(NAME, PHONE)).thenReturn(List.of(socialUser()));

        CustomException ex = assertThrows(CustomException.class,
                () -> passwordResetService.requestResetBySms(smsSendRequest(), IP));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SOCIAL_USER_NO_PASSWORD);
        verify(smsVerificationService, never()).sendCode(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("LOCAL 사용자 매칭 → SMS 인증코드 발송")
    void requestResetBySms_정상_SMS발송() {
        when(userRepository.findAllByNameAndPhone(NAME, PHONE)).thenReturn(List.of(localUser("enc")));

        passwordResetService.requestResetBySms(smsSendRequest(), IP);

        verify(smsVerificationService).sendCode(eq(PHONE), any(), anyString());
    }

    // ─── confirmReset — A-M1 enumeration 차단 (정책 변경 후에도 방어 심층 유지) ──────────

    @Test
    @DisplayName("confirmReset은 코드 검증을 사용자 조회보다 먼저 수행한다 — 코드 무효 시 사용자 조회 안 함 (A-M1)")
    void confirmReset_코드검증_사용자조회보다_선행() {
        PasswordResetConfirmRequest req = confirmRequest(EMAIL, null, "Brand-New1!");
        doThrow(new CustomException(ErrorCode.EXPIRED_SMS_CODE))
                .when(verificationCodeValidator).verify(anyString(), anyString(), anyString(), anyLong(), anyInt());

        CustomException ex = assertThrows(CustomException.class,
                () -> passwordResetService.confirmReset(req, IP, AGENT));

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

    private PasswordResetSmsSendRequest smsSendRequest() {
        PasswordResetSmsSendRequest req = mock(PasswordResetSmsSendRequest.class);
        when(req.getName()).thenReturn(NAME);
        when(req.getPhone()).thenReturn(PHONE);
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
                .name(NAME)
                .phone(PHONE)
                .role(Role.WARD)
                .status(Status.ACTIVE)
                .provider(Provider.LOCAL)
                .build();
    }

    private User socialUser() {
        return User.builder()
                .id("kak123")
                .email(EMAIL)
                .name(NAME)
                .phone(PHONE)
                .role(Role.WARD)
                .status(Status.ACTIVE)
                .provider(Provider.KAKAO)
                .providerId("999")
                .build();
    }
}
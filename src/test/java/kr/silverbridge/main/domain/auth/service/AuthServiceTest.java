package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.dto.LoginRequest;
import kr.silverbridge.main.domain.auth.dto.RegisterRequest;
import kr.silverbridge.main.domain.auth.dto.TokenRefreshRequest;
import kr.silverbridge.main.domain.auth.entity.RefreshToken;
import kr.silverbridge.main.domain.auth.repository.RefreshTokenRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.jwt.JwtTokenProvider;
import kr.silverbridge.main.global.util.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private AccessLogService accessLogService;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @InjectMocks private AuthService authService;

    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_USER_ID = "user-uuid-1234";
    private static final String TEST_IP    = "127.0.0.1";
    private static final String TEST_AGENT = "TestAgent/1.0";

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ─── login ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("존재하지 않는 이메일로 로그인 → USER_NOT_FOUND (잠금 검사 미수행)")
    void login_존재하지않는이메일_USER_NOT_FOUND() {
        LoginRequest req = loginRequest(TEST_EMAIL, "Password1!");
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class,
                () -> authService.login(req, TEST_IP, TEST_AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
        // 가입 안 된 이메일은 잠금 키 자체에 접근하지 않음 (DoS 방지)
        verify(redisTemplate, never()).hasKey(anyString());
    }

    @Test
    @DisplayName("로그인 잠금 상태(user.id 기반)에서 로그인 시도 → LOGIN_LOCKED")
    void login_잠금상태이면_LOGIN_LOCKED() {
        LoginRequest req = loginRequest(TEST_EMAIL, "Password1!");
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(activeUser()));
        when(redisTemplate.hasKey(RedisKeys.LOGIN_LOCK + TEST_USER_ID)).thenReturn(true);

        CustomException ex = assertThrows(CustomException.class,
                () -> authService.login(req, TEST_IP, TEST_AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.LOGIN_LOCKED);
    }

    @Test
    @DisplayName("비활성화 계정으로 로그인 → INACTIVE_USER")
    void login_비활성계정_INACTIVE_USER() {
        LoginRequest req = loginRequest(TEST_EMAIL, "Password1!");
        User inactiveUser = User.builder()
                .id(TEST_USER_ID)
                .email(TEST_EMAIL)
                .password("encodedPassword")
                .name("테스트")
                .role(Role.WARD)
                .status(Status.INACTIVE)
                .provider(Provider.LOCAL)
                .build();

        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(inactiveUser));
        when(redisTemplate.hasKey(RedisKeys.LOGIN_LOCK + TEST_USER_ID)).thenReturn(false);

        CustomException ex = assertThrows(CustomException.class,
                () -> authService.login(req, TEST_IP, TEST_AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INACTIVE_USER);
    }

    @Test
    @DisplayName("비밀번호 불일치 → INVALID_PASSWORD, 실패 횟수 증가 (user.id 기반)")
    void login_비밀번호불일치_INVALID_PASSWORD_실패횟수증가() {
        LoginRequest req = loginRequest(TEST_EMAIL, "WrongPass1!");
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(activeUser()));
        when(redisTemplate.hasKey(RedisKeys.LOGIN_LOCK + TEST_USER_ID)).thenReturn(false);
        when(passwordEncoder.matches("WrongPass1!", "encodedPassword")).thenReturn(false);
        when(valueOperations.increment(RedisKeys.LOGIN_FAIL + TEST_USER_ID)).thenReturn(1L);

        CustomException ex = assertThrows(CustomException.class,
                () -> authService.login(req, TEST_IP, TEST_AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PASSWORD);
        verify(valueOperations).increment(RedisKeys.LOGIN_FAIL + TEST_USER_ID);
    }

    @Test
    @DisplayName("비밀번호 5회 실패 → 로그인 잠금 설정 (user.id 기반)")
    void login_5회실패시_잠금설정() {
        LoginRequest req = loginRequest(TEST_EMAIL, "WrongPass1!");
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(activeUser()));
        when(redisTemplate.hasKey(RedisKeys.LOGIN_LOCK + TEST_USER_ID)).thenReturn(false);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(valueOperations.increment(RedisKeys.LOGIN_FAIL + TEST_USER_ID)).thenReturn(5L);

        assertThrows(CustomException.class, () -> authService.login(req, TEST_IP, TEST_AGENT));

        verify(valueOperations).set(eq(RedisKeys.LOGIN_LOCK + TEST_USER_ID), eq("1"), anyLong(), any());
        verify(redisTemplate).delete(RedisKeys.LOGIN_FAIL + TEST_USER_ID);
    }

    // ─── register ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("이미 사용 중인 이메일로 회원가입 → EMAIL_ALREADY_EXISTS")
    void register_이메일중복_EMAIL_ALREADY_EXISTS() {
        RegisterRequest req = registerRequest(TEST_EMAIL, "01012345678", Role.WARD);
        when(userRepository.existsByEmail(TEST_EMAIL)).thenReturn(true);

        CustomException ex = assertThrows(CustomException.class,
                () -> authService.register(req));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("ADMIN 역할로 회원가입 시도 → INVALID_ROLE")
    void register_ADMIN역할_INVALID_ROLE() {
        RegisterRequest req = registerRequest(TEST_EMAIL, "01012345678", Role.ADMIN);
        when(userRepository.existsByEmail(TEST_EMAIL)).thenReturn(false);
        when(userRepository.existsByPhone("01012345678")).thenReturn(false);

        CustomException ex = assertThrows(CustomException.class,
                () -> authService.register(req));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_ROLE);
    }

    @Test
    @DisplayName("SMS 인증 미완료 상태에서 회원가입 → SMS_NOT_VERIFIED")
    void register_SMS미인증_SMS_NOT_VERIFIED() {
        RegisterRequest req = registerRequest(TEST_EMAIL, "01012345678", Role.WARD);
        when(userRepository.existsByEmail(TEST_EMAIL)).thenReturn(false);
        when(userRepository.existsByPhone("01012345678")).thenReturn(false);
        when(redisTemplate.hasKey(RedisKeys.SMS_VERIFIED + "01012345678")).thenReturn(false);

        CustomException ex = assertThrows(CustomException.class,
                () -> authService.register(req));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SMS_NOT_VERIFIED);
    }

    // ─── refresh ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("DB에 없는 Refresh Token으로 재발급 → INVALID_TOKEN")
    void refresh_존재하지않는토큰_INVALID_TOKEN() {
        TokenRefreshRequest req = tokenRefreshRequest("unknown-token");
        when(refreshTokenRepository.findByToken("unknown-token")).thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class,
                () -> authService.refresh(req));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("만료된 Refresh Token으로 재발급 → EXPIRED_TOKEN, 토큰 삭제")
    void refresh_만료된토큰_EXPIRED_TOKEN_및_삭제() {
        RefreshToken expiredToken = expiredRefreshToken("expired-token");
        TokenRefreshRequest req = tokenRefreshRequest("expired-token");
        when(refreshTokenRepository.findByToken("expired-token")).thenReturn(Optional.of(expiredToken));

        CustomException ex = assertThrows(CustomException.class,
                () -> authService.refresh(req));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.EXPIRED_TOKEN);
        verify(refreshTokenRepository).delete(expiredToken);
    }

    // ─── 헬퍼 메서드 ────────────────────────────────────────────────────────

    private LoginRequest loginRequest(String email, String password) {
        LoginRequest req = mock(LoginRequest.class);
        when(req.getEmail()).thenReturn(email);
        when(req.getPassword()).thenReturn(password);
        return req;
    }

    private RegisterRequest registerRequest(String email, String phone, Role role) {
        RegisterRequest req = mock(RegisterRequest.class);
        when(req.getEmail()).thenReturn(email);
        when(req.getPhone()).thenReturn(phone);
        when(req.getRole()).thenReturn(role);
        return req;
    }

    private TokenRefreshRequest tokenRefreshRequest(String token) {
        TokenRefreshRequest req = mock(TokenRefreshRequest.class);
        when(req.getRefreshToken()).thenReturn(token);
        return req;
    }

    private User activeUser() {
        return User.builder()
                .id(TEST_USER_ID)
                .email(TEST_EMAIL)
                .password("encodedPassword")
                .name("테스트")
                .role(Role.WARD)
                .status(Status.ACTIVE)
                .provider(Provider.LOCAL)
                .build();
    }

    private RefreshToken expiredRefreshToken(String token) {
        return RefreshToken.builder()
                .userId(TEST_USER_ID)
                .token(token)
                .expiresAt(OffsetDateTime.now().minusDays(1))
                .build();
    }
}

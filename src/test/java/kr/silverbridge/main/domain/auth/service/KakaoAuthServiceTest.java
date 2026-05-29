package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.dto.KakaoLoginRequest;
import kr.silverbridge.main.domain.auth.dto.KakaoLoginResponse;
import kr.silverbridge.main.domain.auth.dto.KakaoRegisterRequest;
import kr.silverbridge.main.domain.auth.dto.LoginResponse;
import kr.silverbridge.main.domain.auth.oauth.KakaoOAuthClient;
import kr.silverbridge.main.domain.auth.oauth.KakaoTokenResponse;
import kr.silverbridge.main.domain.auth.oauth.KakaoUserInfoResponse;
import kr.silverbridge.main.domain.auth.repository.RefreshTokenRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Gender;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.jwt.JwtTokenProvider;
import kr.silverbridge.main.global.util.RedisKeys;
import kr.silverbridge.main.global.util.UserIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = Strictness.LENIENT)
class KakaoAuthServiceTest {

    @Mock private KakaoOAuthClient kakaoOAuthClient;
    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private RefreshTokenRevocationService refreshTokenRevocationService;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private AccessLogService accessLogService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private UserIdGenerator userIdGenerator;
    @Mock private SmsService smsService;

    @Mock private KakaoTokenResponse tokenResponse;
    @Mock private KakaoUserInfoResponse userInfo;

    @InjectMocks private KakaoAuthService kakaoAuthService;

    private static final String KAKAO_ID = "3456789012";
    private static final String IP = "127.0.0.1";
    private static final String AGENT = "TestAgent/1.0";

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(kakaoOAuthClient.getToken(anyString(), any())).thenReturn(tokenResponse);
        when(tokenResponse.getAccessToken()).thenReturn("kakao-access");
        when(kakaoOAuthClient.getUserInfo("kakao-access")).thenReturn(userInfo);
        when(userInfo.getId()).thenReturn(3456789012L);
        when(jwtTokenProvider.generateAccessToken(any(), any(), any())).thenReturn("access-jwt");
        when(jwtTokenProvider.generateRefreshToken(any())).thenReturn("refresh-jwt");
        when(jwtTokenProvider.getRemainingExpiration(anyString())).thenReturn(604_800_000L);
        when(userIdGenerator.generate()).thenReturn("kAk123");
    }

    // ─── kakaoLogin ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("기존 카카오 사용자 로그인 → isNewUser=false, 토큰 발급")
    void kakaoLogin_기존사용자_토큰발급() {
        when(userRepository.findByProviderAndProviderId(Provider.KAKAO, KAKAO_ID))
                .thenReturn(Optional.of(activeKakaoUser()));

        KakaoLoginResponse res = kakaoAuthService.kakaoLogin(loginRequest(), IP, AGENT);

        assertThat(res.isNewUser()).isFalse();
        assertThat(res.getAccessToken()).isEqualTo("access-jwt");
        assertThat(res.getRefreshToken()).isEqualTo("refresh-jwt");
        verify(refreshTokenRepository).deleteByUserId("kAk123");
        verify(refreshTokenRepository).save(any());
    }

    @Test
    @DisplayName("기존 카카오 사용자가 INACTIVE → INACTIVE_USER + 모든 refresh token 폐기")
    void kakaoLogin_비활성사용자_INACTIVE_USER() {
        when(userRepository.findByProviderAndProviderId(Provider.KAKAO, KAKAO_ID))
                .thenReturn(Optional.of(inactiveKakaoUser()));

        CustomException ex = assertThrows(CustomException.class,
                () -> kakaoAuthService.kakaoLogin(loginRequest(), IP, AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INACTIVE_USER);
        verify(refreshTokenRevocationService).revokeAll("kAk123");
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("신규 카카오 사용자 → isNewUser=true, kakaoId/email 반환 + Redis pending 저장 (DB 미저장)")
    void kakaoLogin_신규사용자_pending저장() {
        when(userRepository.findByProviderAndProviderId(Provider.KAKAO, KAKAO_ID))
                .thenReturn(Optional.empty());
        when(userInfo.getEmail()).thenReturn("new@kakao.com");
        when(userInfo.getProfileImageUrl()).thenReturn("https://img.kakao/abc");
        when(userRepository.existsByEmail("new@kakao.com")).thenReturn(false);

        KakaoLoginResponse res = kakaoAuthService.kakaoLogin(loginRequest(), IP, AGENT);

        assertThat(res.isNewUser()).isTrue();
        assertThat(res.getKakaoId()).isEqualTo(KAKAO_ID);
        assertThat(res.getEmail()).isEqualTo("new@kakao.com");
        assertThat(res.getName()).isNull();   // 카카오 닉네임 미사용 — 가입 시 본인 실명 직접 입력
        verify(valueOperations).set(eq(RedisKeys.KAKAO_PENDING + KAKAO_ID), eq("new@kakao.com"), anyLong(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("신규 카카오 사용자지만 동일 이메일로 LOCAL 가입이 이미 존재 → EMAIL_ALREADY_EXISTS")
    void kakaoLogin_이메일충돌_EMAIL_ALREADY_EXISTS() {
        when(userRepository.findByProviderAndProviderId(Provider.KAKAO, KAKAO_ID))
                .thenReturn(Optional.empty());
        when(userInfo.getEmail()).thenReturn("dup@example.com");
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        CustomException ex = assertThrows(CustomException.class,
                () -> kakaoAuthService.kakaoLogin(loginRequest(), IP, AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS);
    }

    // ─── kakaoRegister ───────────────────────────────────────────────────────

    @Test
    @DisplayName("카카오 신규 가입 완료 → SMS·세션 검증 통과 후 DB 저장 + 토큰 발급")
    void kakaoRegister_성공_토큰발급() {
        when(valueOperations.get(RedisKeys.KAKAO_PENDING + KAKAO_ID)).thenReturn("kakao@example.com");
        when(userRepository.existsByEmail("kakao@example.com")).thenReturn(false);
        when(userRepository.existsByPhone("01012345678")).thenReturn(false);

        LoginResponse res = kakaoAuthService.kakaoRegister(registerRequest(Role.WARD), IP, AGENT);

        assertThat(res.getAccessToken()).isEqualTo("access-jwt");
        assertThat(res.getRefreshToken()).isEqualTo("refresh-jwt");
        verify(userRepository).save(any(User.class));
        verify(redisTemplate).delete(RedisKeys.KAKAO_PENDING + KAKAO_ID);
        // 모든 검증 통과 시에만 nonce 소비
        verify(smsService).consumeVerification("01012345678", "nonce-uuid");
    }

    @Test
    @DisplayName("카카오 세션(pending) 만료 → KAKAO_SESSION_EXPIRED + SMS nonce 미소비(재시도 보존)")
    void kakaoRegister_세션만료_KAKAO_SESSION_EXPIRED() {
        when(valueOperations.get(RedisKeys.KAKAO_PENDING + KAKAO_ID)).thenReturn(null);

        CustomException ex = assertThrows(CustomException.class,
                () -> kakaoAuthService.kakaoRegister(registerRequest(Role.WARD), IP, AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.KAKAO_SESSION_EXPIRED);
        verify(userRepository, never()).save(any());
        // 세션 만료로 실패해도 SMS 인증 nonce는 소비되지 않아야 한다 — 검증보다 소비가 뒤이므로.
        verify(smsService, never()).consumeVerification(anyString(), anyString());
    }

    @Test
    @DisplayName("SMS 인증 미완료 상태에서 카카오 가입 → SMS_NOT_VERIFIED (세션·중복 검증 통과 후 소비 단계에서 차단)")
    void kakaoRegister_SMS미인증_SMS_NOT_VERIFIED() {
        when(valueOperations.get(RedisKeys.KAKAO_PENDING + KAKAO_ID)).thenReturn("kakao@example.com");
        when(userRepository.existsByEmail("kakao@example.com")).thenReturn(false);
        when(userRepository.existsByPhone("01012345678")).thenReturn(false);
        doThrow(new CustomException(ErrorCode.SMS_NOT_VERIFIED))
                .when(smsService).consumeVerification(eq("01012345678"), anyString());

        CustomException ex = assertThrows(CustomException.class,
                () -> kakaoAuthService.kakaoRegister(registerRequest(Role.WARD), IP, AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SMS_NOT_VERIFIED);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("카카오 가입 시 ADMIN 역할 선택 → INVALID_ROLE + SMS nonce 미소비")
    void kakaoRegister_ADMIN역할_INVALID_ROLE() {
        when(valueOperations.get(RedisKeys.KAKAO_PENDING + KAKAO_ID)).thenReturn("kakao@example.com");

        CustomException ex = assertThrows(CustomException.class,
                () -> kakaoAuthService.kakaoRegister(registerRequest(Role.ADMIN), IP, AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_ROLE);
        verify(userRepository, never()).save(any());
        verify(smsService, never()).consumeVerification(anyString(), anyString());
    }

    @Test
    @DisplayName("이메일 중복으로 카카오 가입 실패 → EMAIL_ALREADY_EXISTS + SMS nonce 미소비(재시도 보존)")
    void kakaoRegister_이메일중복_nonce미소비() {
        when(valueOperations.get(RedisKeys.KAKAO_PENDING + KAKAO_ID)).thenReturn("kakao@example.com");
        when(userRepository.existsByEmail("kakao@example.com")).thenReturn(true);

        CustomException ex = assertThrows(CustomException.class,
                () -> kakaoAuthService.kakaoRegister(registerRequest(Role.WARD), IP, AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS);
        verify(userRepository, never()).save(any());
        // 중복 실패 시 nonce가 소비되면 재인증 없이는 재시도가 막힌다 — 소비되지 않아야 함(회귀 가드).
        verify(smsService, never()).consumeVerification(anyString(), anyString());
    }

    @Test
    @DisplayName("전화번호 중복으로 카카오 가입 실패 → PHONE_ALREADY_EXISTS + SMS nonce 미소비")
    void kakaoRegister_전화번호중복_nonce미소비() {
        when(valueOperations.get(RedisKeys.KAKAO_PENDING + KAKAO_ID)).thenReturn("kakao@example.com");
        when(userRepository.existsByEmail("kakao@example.com")).thenReturn(false);
        when(userRepository.existsByPhone("01012345678")).thenReturn(true);

        CustomException ex = assertThrows(CustomException.class,
                () -> kakaoAuthService.kakaoRegister(registerRequest(Role.WARD), IP, AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PHONE_ALREADY_EXISTS);
        verify(userRepository, never()).save(any());
        verify(smsService, never()).consumeVerification(anyString(), anyString());
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────

    private KakaoLoginRequest loginRequest() {
        KakaoLoginRequest req = org.mockito.Mockito.mock(KakaoLoginRequest.class);
        when(req.getCode()).thenReturn("auth-code");
        return req;
    }

    private KakaoRegisterRequest registerRequest(Role role) {
        KakaoRegisterRequest req = org.mockito.Mockito.mock(KakaoRegisterRequest.class);
        when(req.getKakaoId()).thenReturn(KAKAO_ID);
        when(req.getName()).thenReturn("홍길동");
        when(req.getPhone()).thenReturn("01012345678");
        when(req.getVerificationNonce()).thenReturn("nonce-uuid");
        when(req.getRole()).thenReturn(role);
        when(req.getProfileImageUrl()).thenReturn(null);
        when(req.getAddress()).thenReturn("서울특별시 강남구 테헤란로 123");
        when(req.getAddressDetail()).thenReturn("101동 202호");
        when(req.getGender()).thenReturn(Gender.MALE);
        when(req.getBirthDate()).thenReturn(LocalDate.of(1990, 3, 15));
        when(req.getPostcode()).thenReturn("06236");
        return req;
    }

    private User activeKakaoUser() {
        return kakaoUser(Status.ACTIVE);
    }

    private User inactiveKakaoUser() {
        return kakaoUser(Status.INACTIVE);
    }

    private User kakaoUser(Status status) {
        return User.builder()
                .id("kAk123")
                .email("kakao@example.com")
                .name("카카오사용자")
                .role(Role.GUARDIAN)
                .status(status)
                .provider(Provider.KAKAO)
                .providerId(KAKAO_ID)
                .build();
    }
}

package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.dto.KakaoLoginRequest;
import kr.silverbridge.main.domain.auth.dto.KakaoLoginResponse;
import kr.silverbridge.main.domain.auth.dto.KakaoRegisterRequest;
import kr.silverbridge.main.domain.auth.dto.LoginResponse;
import kr.silverbridge.main.domain.auth.event.KakaoRegisteredEvent;
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
import kr.silverbridge.main.domain.user.service.UserIdGenerator;
import org.junit.jupiter.api.BeforeEach;
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
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private LoginSupersedeMarker loginSupersedeMarker;

    @Mock private KakaoTokenResponse tokenResponse;
    @Mock private KakaoUserInfoResponse userInfo;

    @InjectMocks private KakaoAuthService kakaoAuthService;

    private static final String KAKAO_ID = "3456789012";
    private static final String IP = "127.0.0.1";
    private static final String AGENT = "TestAgent/1.0";
    private static final String PENDING_TOKEN = "pending-token-from-login-response";

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
        // 밀려난 기기의 갱신이 재사용 감지로 가지 않게 로그인 시각을 남긴다 (AUTH-G03)
        verify(loginSupersedeMarker).markLogin("kAk123", "refresh-jwt");
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
        // pending 값 = pendingToken 해시 + 이메일 - 원문 토큰은 응답으로만 나간다 (AUTH-G07)
        assertThat(res.getPendingToken()).isNotBlank().hasSizeGreaterThanOrEqualTo(40);
        verify(valueOperations).set(eq(RedisKeys.KAKAO_PENDING + KAKAO_ID),
                eq(AuthInputNormalizer.sha256Hex(res.getPendingToken()) + "|new@kakao.com"), anyLong(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("신규 카카오 사용자 - 로그인마다 pendingToken이 다르다(추측 불가 일회용 값)")
    void kakaoLogin_신규사용자_pendingToken_매번다름() {
        when(userRepository.findByProviderAndProviderId(Provider.KAKAO, KAKAO_ID)).thenReturn(Optional.empty());
        when(userInfo.getEmail()).thenReturn("new@kakao.com");

        String first = kakaoAuthService.kakaoLogin(loginRequest(), IP, AGENT).getPendingToken();
        String second = kakaoAuthService.kakaoLogin(loginRequest(), IP, AGENT).getPendingToken();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("신규 카카오 사용자 - 이메일 대소문자를 소문자로 맞춰 중복 검사·저장한다 (AUTH-G09)")
    void kakaoLogin_신규사용자_이메일소문자() {
        when(userRepository.findByProviderAndProviderId(Provider.KAKAO, KAKAO_ID)).thenReturn(Optional.empty());
        when(userInfo.getEmail()).thenReturn(" New@Kakao.COM ");

        KakaoLoginResponse res = kakaoAuthService.kakaoLogin(loginRequest(), IP, AGENT);

        assertThat(res.getEmail()).isEqualTo("new@kakao.com");
        verify(userRepository).existsByEmail("new@kakao.com");
    }

    @Test
    @DisplayName("신규 카카오 사용자 - 카카오가 준 http CDN 이미지는 https로 올리고, CDN 밖 주소는 버린다")
    void kakaoLogin_신규사용자_프로필이미지정규화() {
        when(userRepository.findByProviderAndProviderId(Provider.KAKAO, KAKAO_ID)).thenReturn(Optional.empty());
        when(userInfo.getEmail()).thenReturn("new@kakao.com");
        when(userInfo.getProfileImageUrl()).thenReturn("http://k.kakaocdn.net/dn/abc/img_640x640.jpg");

        assertThat(kakaoAuthService.kakaoLogin(loginRequest(), IP, AGENT).getProfileImageUrl())
                .isEqualTo("https://k.kakaocdn.net/dn/abc/img_640x640.jpg");

        when(userInfo.getProfileImageUrl()).thenReturn("https://evil.example.com/a.jpg");
        assertThat(kakaoAuthService.kakaoLogin(loginRequest(), IP, AGENT).getProfileImageUrl()).isNull();
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
        when(valueOperations.get(RedisKeys.KAKAO_PENDING + KAKAO_ID)).thenReturn(pendingValue("kakao@example.com"));
        when(userRepository.existsByEmail("kakao@example.com")).thenReturn(false);
        when(userRepository.existsByPhone("01012345678")).thenReturn(false);

        LoginResponse res = kakaoAuthService.kakaoRegister(registerRequest(Role.WARD), IP, AGENT);

        assertThat(res.getAccessToken()).isEqualTo("access-jwt");
        assertThat(res.getRefreshToken()).isEqualTo("refresh-jwt");
        verify(userRepository).save(any(User.class));
        verify(redisTemplate).delete(RedisKeys.KAKAO_PENDING + KAKAO_ID);
        // 모든 검증 통과 시에만 nonce 소비
        verify(smsService).consumeVerification("01012345678", "nonce-uuid");
        // KAKAO_LOGIN 접속로그는 가입 트랜잭션 커밋 후로 미룬다 → 이벤트만 발행한다.
        verify(eventPublisher).publishEvent(any(KakaoRegisteredEvent.class));
        // 회귀 가드: 가입 트랜잭션 안에서 직접 accessLogService.log()(REQUIRES_NEW)를 호출하면
        // 아직 커밋되지 않은 users 행을 별도 트랜잭션이 보지 못해 FK 위반(SQLState 23503)이 난다 → 직접 호출 금지.
        verify(accessLogService, never()).log(anyString(), any(), anyString(), anyString());
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
        when(valueOperations.get(RedisKeys.KAKAO_PENDING + KAKAO_ID)).thenReturn(pendingValue("kakao@example.com"));
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
        when(valueOperations.get(RedisKeys.KAKAO_PENDING + KAKAO_ID)).thenReturn(pendingValue("kakao@example.com"));

        CustomException ex = assertThrows(CustomException.class,
                () -> kakaoAuthService.kakaoRegister(registerRequest(Role.ADMIN), IP, AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_ROLE);
        verify(userRepository, never()).save(any());
        verify(smsService, never()).consumeVerification(anyString(), anyString());
    }

    @Test
    @DisplayName("이메일 중복으로 카카오 가입 실패 → EMAIL_ALREADY_EXISTS + SMS nonce 미소비(재시도 보존)")
    void kakaoRegister_이메일중복_nonce미소비() {
        when(valueOperations.get(RedisKeys.KAKAO_PENDING + KAKAO_ID)).thenReturn(pendingValue("kakao@example.com"));
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
        when(valueOperations.get(RedisKeys.KAKAO_PENDING + KAKAO_ID)).thenReturn(pendingValue("kakao@example.com"));
        when(userRepository.existsByEmail("kakao@example.com")).thenReturn(false);
        when(userRepository.existsByPhone("01012345678")).thenReturn(true);

        CustomException ex = assertThrows(CustomException.class,
                () -> kakaoAuthService.kakaoRegister(registerRequest(Role.WARD), IP, AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PHONE_ALREADY_EXISTS);
        verify(userRepository, never()).save(any());
        verify(smsService, never()).consumeVerification(anyString(), anyString());
    }

    @Test
    @DisplayName("pendingToken이 다르면 kakaoId를 알아도 가입을 완료할 수 없다 → KAKAO_SESSION_EXPIRED, nonce·세션 보존 (AUTH-G07)")
    void kakaoRegister_pendingToken불일치_거절() {
        when(valueOperations.get(RedisKeys.KAKAO_PENDING + KAKAO_ID)).thenReturn(pendingValue("kakao@example.com"));
        KakaoRegisterRequest req = registerRequest(Role.WARD);
        when(req.getPendingToken()).thenReturn("attacker-guess");

        CustomException ex = assertThrows(CustomException.class,
                () -> kakaoAuthService.kakaoRegister(req, IP, AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.KAKAO_SESSION_EXPIRED);
        verify(smsService, never()).consumeVerification(anyString(), anyString());
        verify(userRepository, never()).save(any());
        // 본인의 가입 세션은 지우지 않는다 - 제3자 시도로 본인 가입이 막히면 안 된다
        verify(redisTemplate, never()).delete(RedisKeys.KAKAO_PENDING + KAKAO_ID);
    }

    @Test
    @DisplayName("pendingToken 누락 → KAKAO_SESSION_EXPIRED")
    void kakaoRegister_pendingToken누락_거절() {
        when(valueOperations.get(RedisKeys.KAKAO_PENDING + KAKAO_ID)).thenReturn(pendingValue("kakao@example.com"));
        KakaoRegisterRequest req = registerRequest(Role.WARD);
        when(req.getPendingToken()).thenReturn(null);

        CustomException ex = assertThrows(CustomException.class,
                () -> kakaoAuthService.kakaoRegister(req, IP, AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.KAKAO_SESSION_EXPIRED);
        verify(smsService, never()).consumeVerification(anyString(), anyString());
    }

    @Test
    @DisplayName("배포 전 옛 형식(이메일만 저장된) 세션 → 대조할 토큰이 없어 KAKAO_SESSION_EXPIRED")
    void kakaoRegister_옛형식세션_만료처리() {
        when(valueOperations.get(RedisKeys.KAKAO_PENDING + KAKAO_ID)).thenReturn("kakao@example.com");

        CustomException ex = assertThrows(CustomException.class,
                () -> kakaoAuthService.kakaoRegister(registerRequest(Role.WARD), IP, AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.KAKAO_SESSION_EXPIRED);
        verify(smsService, never()).consumeVerification(anyString(), anyString());
    }

    @Test
    @DisplayName("카카오 CDN 밖의 프로필 이미지 주소 → INVALID_INPUT, 문자 인증은 소비되지 않는다 (AUTH-G22·USER-G03)")
    void kakaoRegister_허용밖이미지주소_nonce미소비() {
        when(valueOperations.get(RedisKeys.KAKAO_PENDING + KAKAO_ID)).thenReturn(pendingValue("kakao@example.com"));
        KakaoRegisterRequest req = registerRequest(Role.WARD);
        when(req.getProfileImageUrl()).thenReturn("https://x.example.com/any/victim-file.png");

        CustomException ex = assertThrows(CustomException.class,
                () -> kakaoAuthService.kakaoRegister(req, IP, AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
        verify(smsService, never()).consumeVerification(anyString(), anyString());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("이름이 보이지 않는 문자뿐이면 INVALID_INPUT, nonce 미소비 / 앞뒤 공백은 정리해 저장 (AUTH-G13)")
    void kakaoRegister_이름정규화() {
        when(valueOperations.get(RedisKeys.KAKAO_PENDING + KAKAO_ID)).thenReturn(pendingValue("kakao@example.com"));
        KakaoRegisterRequest invisible = registerRequest(Role.WARD);
        when(invisible.getName()).thenReturn("\u200B\u3000");

        CustomException ex = assertThrows(CustomException.class,
                () -> kakaoAuthService.kakaoRegister(invisible, IP, AGENT));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
        verify(smsService, never()).consumeVerification(anyString(), anyString());

        KakaoRegisterRequest padded = registerRequest(Role.WARD);
        when(padded.getName()).thenReturn("  홍길동\u200B ");
        when(padded.getProfileImageUrl()).thenReturn("http://k.kakaocdn.net/dn/a.jpg");
        kakaoAuthService.kakaoRegister(padded, IP, AGENT);

        org.mockito.ArgumentCaptor<User> saved = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("홍길동");
        assertThat(saved.getValue().getProfileImage()).isEqualTo("https://k.kakaocdn.net/dn/a.jpg");
    }

    @Test
    @DisplayName("프로필 이미지 주소 검사 - kakaocdn.net과 하위 도메인만, 500자 이하, 사용자정보·포트 없는 주소")
    void toAllowedProfileImageUrl_경계() {
        assertThat(KakaoAuthService.toAllowedProfileImageUrl(null)).isNull();
        assertThat(KakaoAuthService.toAllowedProfileImageUrl("  ")).isNull();
        assertThat(KakaoAuthService.toAllowedProfileImageUrl("https://kakaocdn.net/a.png")).isEqualTo("https://kakaocdn.net/a.png");
        assertThat(KakaoAuthService.toAllowedProfileImageUrl("https://img1.kakaocdn.net/thumb/a.png?x=1"))
                .isEqualTo("https://img1.kakaocdn.net/thumb/a.png?x=1");

        for (String bad : new String[]{
                "https://kakaocdn.net.evil.com/a.png",
                "https://evilkakaocdn.net/a.png",
                "https://user@k.kakaocdn.net/a.png",
                "https://k.kakaocdn.net:8443/a.png",
                "ftp://k.kakaocdn.net/a.png",
                "javascript:alert(1)",
                "not a url",
                "https://k.kakaocdn.net/" + "a".repeat(480)}) {
            CustomException ex = assertThrows(CustomException.class,
                    () -> KakaoAuthService.toAllowedProfileImageUrl(bad), bad);
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
        }
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
        when(req.getPendingToken()).thenReturn(PENDING_TOKEN);
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

    private static String pendingValue(String email) {
        return AuthInputNormalizer.sha256Hex(PENDING_TOKEN) + "|" + email;
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

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
import kr.silverbridge.main.global.util.RedisCounter;
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
    @Mock private RefreshTokenRevocationService refreshTokenRevocationService;
    @Mock private AccessLogService accessLogService;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private RedisCounter redisCounter;
    @Mock private SmsService smsService;
    @Mock private kr.silverbridge.main.domain.auth.config.AuthLoginProperties authLoginProperties;
    @Mock private LoginSupersedeMarker loginSupersedeMarker;
    @Mock private kr.silverbridge.main.domain.user.service.UserIdGenerator userIdGenerator;

    @InjectMocks private AuthService authService;

    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_USER_ID = "user-uuid-1234";
    private static final String TEST_IP    = "127.0.0.1";
    private static final String TEST_AGENT = "TestAgent/1.0";

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(authLoginProperties.getMaxAttempts()).thenReturn(5);
        when(authLoginProperties.getLockTtlMinutes()).thenReturn(30L);
    }

    // ─── login ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("존재하지 않는 이메일로 로그인 → INVALID_CREDENTIALS, 이메일 해시 키로 시도를 세고 더미 BCrypt 비교 (AUTH-G25)")
    void login_존재하지않는이메일_INVALID_CREDENTIALS() {
        LoginRequest req = loginRequest(TEST_EMAIL, "Password1!");
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("dummy-hash");
        String hashKey = AuthInputNormalizer.sha256Hex(TEST_EMAIL);
        when(redisCounter.incrementWithTtl(eq(RedisKeys.LOGIN_FAIL + hashKey), anyLong())).thenReturn(1L);

        CustomException ex = assertThrows(CustomException.class,
                () -> authService.login(req, TEST_IP, TEST_AGENT));

        // 가입 안 된 이메일과 비밀번호 불일치는 동일 응답으로 통합
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        // 가입 이메일과 같은 잠금 검사·카운터·BCrypt 비교를 거친다 - 키는 user.id가 아니라 정규화 이메일의 해시
        verify(redisTemplate).hasKey(RedisKeys.LOGIN_LOCK + hashKey);
        verify(passwordEncoder).matches("Password1!", "dummy-hash");
    }

    @Test
    @DisplayName("미가입 이메일도 5번 틀린 뒤 6번째는 429 - 가입 이메일과 반응이 같다 (AUTH-G25)")
    void login_미가입이메일_6번째_LOGIN_LOCKED() {
        LoginRequest req = loginRequest(TEST_EMAIL, "Wrong1!");
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.empty());
        String hashKey = AuthInputNormalizer.sha256Hex(TEST_EMAIL);

        // 5번째 실패 - 잠금을 건다
        when(redisCounter.incrementWithTtl(eq(RedisKeys.LOGIN_FAIL + hashKey), anyLong())).thenReturn(5L);
        CustomException fifth = assertThrows(CustomException.class, () -> authService.login(req, TEST_IP, TEST_AGENT));
        assertThat(fifth.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        verify(valueOperations).set(eq(RedisKeys.LOGIN_LOCK + hashKey), eq("1"), anyLong(), any());

        // 6번째 - 잠금 키가 있어 비교 없이 429
        when(redisTemplate.hasKey(RedisKeys.LOGIN_LOCK + hashKey)).thenReturn(true);
        CustomException sixth = assertThrows(CustomException.class, () -> authService.login(req, TEST_IP, TEST_AGENT));
        assertThat(sixth.getErrorCode()).isEqualTo(ErrorCode.LOGIN_LOCKED);
    }

    @Test
    @DisplayName("대문자·공백이 섞인 이메일로 로그인해도 소문자로 정규화해 조회한다 (AUTH-G09)")
    void login_이메일정규화() {
        LoginRequest req = loginRequest("  Test@Example.COM ", "Password1!");
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(activeUser()));
        when(redisCounter.incrementWithTtl(anyString(), anyLong())).thenReturn(1L);
        when(passwordEncoder.matches("Password1!", "encodedPassword")).thenReturn(true);
        when(jwtTokenProvider.generateRefreshToken(TEST_USER_ID)).thenReturn("new-refresh");

        authService.login(req, TEST_IP, TEST_AGENT);

        verify(userRepository).findByEmail(TEST_EMAIL);
    }

    @Test
    @DisplayName("시도 횟수는 비밀번호 비교 전에 예약한다 (AUTH-G05)")
    void login_시도예약이_비교보다_먼저() {
        LoginRequest req = loginRequest(TEST_EMAIL, "WrongPass1!");
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(activeUser()));
        when(redisCounter.incrementWithTtl(eq(RedisKeys.LOGIN_FAIL + TEST_USER_ID), anyLong())).thenReturn(2L);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThrows(CustomException.class, () -> authService.login(req, TEST_IP, TEST_AGENT));

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(redisTemplate, redisCounter, passwordEncoder);
        inOrder.verify(redisTemplate).hasKey(RedisKeys.LOGIN_LOCK + TEST_USER_ID);
        inOrder.verify(redisCounter).incrementWithTtl(eq(RedisKeys.LOGIN_FAIL + TEST_USER_ID), anyLong());
        inOrder.verify(passwordEncoder).matches("WrongPass1!", "encodedPassword");
    }

    @Test
    @DisplayName("예약 결과가 한도를 넘으면 비밀번호를 비교하지 않고 429 + 잠금 키 설정 (AUTH-G05)")
    void login_예약초과_비교없이_LOGIN_LOCKED() {
        LoginRequest req = loginRequest(TEST_EMAIL, "Password1!");
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(activeUser()));
        when(redisCounter.incrementWithTtl(eq(RedisKeys.LOGIN_FAIL + TEST_USER_ID), anyLong())).thenReturn(6L);

        CustomException ex = assertThrows(CustomException.class,
                () -> authService.login(req, TEST_IP, TEST_AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.LOGIN_LOCKED);
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(valueOperations).setIfAbsent(eq(RedisKeys.LOGIN_LOCK + TEST_USER_ID), eq("1"), anyLong(), any());
    }

    @Test
    @DisplayName("실패 4회 상태에서 틀린 비밀번호 8건 동시 → 비교는 1건뿐, 나머지는 429 (AUTH-G05)")
    void login_동시요청_비교는_한도까지만() throws Exception {
        java.util.concurrent.atomic.AtomicLong counter = new java.util.concurrent.atomic.AtomicLong(4);
        java.util.Set<String> keys = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.concurrent.atomic.AtomicInteger comparisons = new java.util.concurrent.atomic.AtomicInteger();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(activeUser()));
        when(redisTemplate.hasKey(anyString())).thenAnswer(inv -> keys.contains(inv.<String>getArgument(0)));
        when(redisCounter.incrementWithTtl(anyString(), anyLong())).thenAnswer(inv -> counter.incrementAndGet());
        org.mockito.Mockito.doAnswer(inv -> keys.add(inv.getArgument(0)))
                .when(valueOperations).set(anyString(), anyString(), anyLong(), any());
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any()))
                .thenAnswer(inv -> keys.add(inv.getArgument(0)));
        when(passwordEncoder.matches(anyString(), anyString())).thenAnswer(inv -> {
            comparisons.incrementAndGet();
            Thread.sleep(50); // BCrypt 구간
            return false;
        });

        int threads = 8;
        java.util.List<LoginRequest> requests = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            requests.add(loginRequest(TEST_EMAIL, "WrongPass1!"));
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<ErrorCode>> results = new java.util.ArrayList<>();
        for (LoginRequest req : requests) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    authService.login(req, TEST_IP, TEST_AGENT);
                    return null;
                } catch (CustomException e) {
                    return e.getErrorCode();
                }
            }));
        }
        start.countDown();
        java.util.List<ErrorCode> codes = new java.util.ArrayList<>();
        for (var f : results) {
            codes.add(f.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(comparisons.get()).isEqualTo(1);
        assertThat(codes).filteredOn(c -> c == ErrorCode.INVALID_CREDENTIALS).hasSize(1);
        assertThat(codes).filteredOn(c -> c == ErrorCode.LOGIN_LOCKED).hasSize(threads - 1);
    }

    @Test
    @DisplayName("로그인 성공 → 실패 카운터 삭제 + 로그인 시각 기록(AUTH-G03)")
    void login_성공_카운터삭제_로그인시각기록() {
        LoginRequest req = loginRequest(TEST_EMAIL, "Password1!");
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(activeUser()));
        when(redisCounter.incrementWithTtl(anyString(), anyLong())).thenReturn(3L);
        when(passwordEncoder.matches("Password1!", "encodedPassword")).thenReturn(true);
        when(jwtTokenProvider.generateRefreshToken(TEST_USER_ID)).thenReturn("new-refresh");

        authService.login(req, TEST_IP, TEST_AGENT);

        verify(redisTemplate).delete(RedisKeys.LOGIN_FAIL + TEST_USER_ID);
        verify(refreshTokenRepository).deleteByUserId(TEST_USER_ID);
        verify(loginSupersedeMarker).markLogin(TEST_USER_ID, "new-refresh");
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
    @DisplayName("비활성화 계정이라도 비밀번호 검증 통과 후에만 INACTIVE_USER 노출 (enumeration 차단)")
    void login_비활성계정_비밀번호통과후_INACTIVE_USER() {
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
        when(passwordEncoder.matches("Password1!", "encodedPassword")).thenReturn(true);

        CustomException ex = assertThrows(CustomException.class,
                () -> authService.login(req, TEST_IP, TEST_AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INACTIVE_USER);
    }

    @Test
    @DisplayName("관리자가 이용 제한한 계정(RESTRICTED)도 로그인이 막힌다 - INACTIVE 등호 비교로 두면 조용히 뚫린다")
    void login_이용제한계정_INACTIVE_USER() {
        LoginRequest req = loginRequest(TEST_EMAIL, "Password1!");
        User restrictedUser = User.builder()
                .id(TEST_USER_ID)
                .email(TEST_EMAIL)
                .password("encodedPassword")
                .name("테스트")
                .role(Role.WARD)
                .status(Status.RESTRICTED)
                .provider(Provider.LOCAL)
                .build();

        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(restrictedUser));
        when(redisTemplate.hasKey(RedisKeys.LOGIN_LOCK + TEST_USER_ID)).thenReturn(false);
        when(passwordEncoder.matches("Password1!", "encodedPassword")).thenReturn(true);

        CustomException ex = assertThrows(CustomException.class,
                () -> authService.login(req, TEST_IP, TEST_AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INACTIVE_USER);
        // 남아 있는 refresh 토큰도 함께 끊는다
        verify(refreshTokenRevocationService).revokeAll(TEST_USER_ID);
    }

    @Test
    @DisplayName("비활성 계정 + 비밀번호 틀림 → INVALID_CREDENTIALS (정지 사실 노출 안 됨)")
    void login_비활성계정_비밀번호틀림_INVALID_CREDENTIALS() {
        LoginRequest req = loginRequest(TEST_EMAIL, "WrongPass1!");
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
        when(passwordEncoder.matches("WrongPass1!", "encodedPassword")).thenReturn(false);
        when(redisCounter.incrementWithTtl(eq(RedisKeys.LOGIN_FAIL + TEST_USER_ID), anyLong())).thenReturn(1L);

        CustomException ex = assertThrows(CustomException.class,
                () -> authService.login(req, TEST_IP, TEST_AGENT));

        // 비밀번호가 틀린 단계에서 차단되므로 INACTIVE 사실이 새지 않음
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("비밀번호 불일치 → INVALID_CREDENTIALS, 실패 횟수 증가 (user.id 기반)")
    void login_비밀번호불일치_INVALID_CREDENTIALS_실패횟수증가() {
        LoginRequest req = loginRequest(TEST_EMAIL, "WrongPass1!");
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(activeUser()));
        when(redisTemplate.hasKey(RedisKeys.LOGIN_LOCK + TEST_USER_ID)).thenReturn(false);
        when(passwordEncoder.matches("WrongPass1!", "encodedPassword")).thenReturn(false);
        when(redisCounter.incrementWithTtl(eq(RedisKeys.LOGIN_FAIL + TEST_USER_ID), anyLong())).thenReturn(1L);

        CustomException ex = assertThrows(CustomException.class,
                () -> authService.login(req, TEST_IP, TEST_AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        // INCR/EXPIRE 원자 처리로 RedisCounter 경유 (M-4 패턴 일관)
        verify(redisCounter).incrementWithTtl(eq(RedisKeys.LOGIN_FAIL + TEST_USER_ID), anyLong());
    }

    @Test
    @DisplayName("비밀번호 5회 실패 → 로그인 잠금 설정 (user.id 기반), 카운터는 지우지 않는다(동시 요청 재예약 방지)")
    void login_5회실패시_잠금설정() {
        LoginRequest req = loginRequest(TEST_EMAIL, "WrongPass1!");
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(activeUser()));
        when(redisTemplate.hasKey(RedisKeys.LOGIN_LOCK + TEST_USER_ID)).thenReturn(false);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(redisCounter.incrementWithTtl(eq(RedisKeys.LOGIN_FAIL + TEST_USER_ID), anyLong())).thenReturn(5L);

        CustomException ex = assertThrows(CustomException.class, () -> authService.login(req, TEST_IP, TEST_AGENT));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        verify(valueOperations).set(eq(RedisKeys.LOGIN_LOCK + TEST_USER_ID), eq("1"), anyLong(), any());
        verify(redisTemplate, never()).delete(RedisKeys.LOGIN_FAIL + TEST_USER_ID);
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
    @DisplayName("SMS 인증 nonce 불일치 상태에서 회원가입 → SMS_NOT_VERIFIED")
    void register_SMS미인증_SMS_NOT_VERIFIED() {
        RegisterRequest req = registerRequest(TEST_EMAIL, "01012345678", Role.WARD);
        when(userRepository.existsByEmail(TEST_EMAIL)).thenReturn(false);
        when(userRepository.existsByPhone("01012345678")).thenReturn(false);
        org.mockito.Mockito.doThrow(new CustomException(ErrorCode.SMS_NOT_VERIFIED))
                .when(smsService).consumeVerification(eq("01012345678"), anyString());

        CustomException ex = assertThrows(CustomException.class,
                () -> authService.register(req));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SMS_NOT_VERIFIED);
    }

    @Test
    @DisplayName("카카오 대체 이메일 형식(대소문자 무시)으로 일반 가입 → INVALID_INPUT, nonce 미소비 (AUTH-G08)")
    void register_카카오대체이메일_거절() {
        RegisterRequest req = registerRequest("Kakao_12345@KAKAO.com", "01012345678", Role.WARD);

        CustomException ex = assertThrows(CustomException.class, () -> authService.register(req));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
        verify(smsService, never()).consumeVerification(anyString(), anyString());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("가입 - 이메일은 소문자로 중복 검사·저장, 이름은 앞뒤 공백·제로폭 문자를 정리해 저장 (AUTH-G09·AUTH-G13)")
    void register_이메일이름_정규화저장() {
        RegisterRequest req = registerRequest(" User@Example.COM ", "01012345678", Role.WARD);
        when(req.getName()).thenReturn("\u200B홍길동 ");
        when(req.getPassword()).thenReturn("Password1!");
        when(userIdGenerator.generate()).thenReturn("abc123");

        authService.register(req);

        verify(userRepository).existsByEmail("user@example.com");
        org.mockito.ArgumentCaptor<User> saved = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("user@example.com");
        assertThat(saved.getValue().getName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("가입 - 보이지 않는 문자만 든 이름 → INVALID_INPUT, nonce 미소비 (AUTH-G13)")
    void register_보이지않는이름_거절() {
        RegisterRequest req = registerRequest(TEST_EMAIL, "01012345678", Role.WARD);
        when(req.getName()).thenReturn("\u200B\u3000 ");

        CustomException ex = assertThrows(CustomException.class, () -> authService.register(req));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
        verify(smsService, never()).consumeVerification(anyString(), anyString());
    }

    @Test
    @DisplayName("이메일 중복 확인도 소문자 기준 - 대문자 이메일로 확인해도 기존 계정과 겹치면 409 (AUTH-G09)")
    void checkEmail_대소문자무시() {
        kr.silverbridge.main.domain.auth.dto.EmailCheckRequest req =
                mock(kr.silverbridge.main.domain.auth.dto.EmailCheckRequest.class);
        when(req.getEmail()).thenReturn("TEST@Example.com");
        when(userRepository.existsByEmail(TEST_EMAIL)).thenReturn(true);

        CustomException ex = assertThrows(CustomException.class, () -> authService.checkEmail(req));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("아이디 찾기 - 이름 앞뒤 공백을 정리해 조회한다 (AUTH-G13·FEUX-G11)")
    void findEmail_이름정규화조회() {
        kr.silverbridge.main.domain.auth.dto.FindEmailRequest req =
                mock(kr.silverbridge.main.domain.auth.dto.FindEmailRequest.class);
        when(req.getName()).thenReturn(" 홍길동\u3000");
        when(req.getPhone()).thenReturn("01012345678");
        when(userRepository.findAllByNameAndPhone("홍길동", "01012345678")).thenReturn(java.util.List.of());

        assertThrows(CustomException.class, () -> authService.findEmail(req));

        verify(userRepository).findAllByNameAndPhone("홍길동", "01012345678");
    }

    // ─── refresh ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("DB에 없는 Refresh Token으로 재발급 → INVALID_TOKEN (변조/만료된 토큰은 재사용 감지 미적용)")
    void refresh_존재하지않는토큰_INVALID_TOKEN() {
        TokenRefreshRequest req = tokenRefreshRequest("unknown-token");
        when(refreshTokenRepository.findByToken("unknown-token")).thenReturn(Optional.empty());
        // JWT 자체가 변조/만료된 경우 재사용 감지 대상이 아님
        when(jwtTokenProvider.validateToken("unknown-token"))
                .thenThrow(new CustomException(ErrorCode.INVALID_TOKEN));

        CustomException ex = assertThrows(CustomException.class,
                () -> authService.refresh(req));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
        verify(refreshTokenRevocationService, never()).revokeAll(anyString());
        verify(accessLogService, never()).log(anyString(), eq(kr.silverbridge.main.global.enums.AccessAction.TOKEN_REUSE_DETECTED));
    }

    @Test
    @DisplayName("DB에 없는 Refresh Token이지만 JWT 자체는 유효하고 같은 userId의 다른 token이 남아있으면 재사용 감지 → 모든 token 폐기")
    void refresh_재사용감지_사용자모든토큰폐기() {
        TokenRefreshRequest req = tokenRefreshRequest("stolen-old-token");
        when(refreshTokenRepository.findByToken("stolen-old-token")).thenReturn(Optional.empty());
        when(jwtTokenProvider.validateToken("stolen-old-token")).thenReturn(true);
        when(jwtTokenProvider.isRefreshToken("stolen-old-token")).thenReturn(true);
        when(jwtTokenProvider.getUserId("stolen-old-token")).thenReturn(TEST_USER_ID);
        when(refreshTokenRepository.existsByUserId(TEST_USER_ID)).thenReturn(true);

        CustomException ex = assertThrows(CustomException.class,
                () -> authService.refresh(req));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
        // REQUIRES_NEW 분리로 caller throw에도 폐기는 유지된다 — revocation 호출 자체를 검증
        verify(refreshTokenRevocationService).revokeAll(TEST_USER_ID);
        verify(accessLogService).log(TEST_USER_ID, kr.silverbridge.main.global.enums.AccessAction.TOKEN_REUSE_DETECTED);
    }

    @Test
    @DisplayName("다른 기기 로그인으로 밀려난 refresh → INVALID_TOKEN만, 새 기기 세션은 폐기하지 않는다 (AUTH-G03)")
    void refresh_로그인으로밀려난토큰_폐기없이_INVALID_TOKEN() {
        TokenRefreshRequest req = tokenRefreshRequest("superseded-token");
        when(refreshTokenRepository.findByToken("superseded-token")).thenReturn(Optional.empty());
        when(jwtTokenProvider.validateToken("superseded-token")).thenReturn(true);
        when(jwtTokenProvider.isRefreshToken("superseded-token")).thenReturn(true);
        when(jwtTokenProvider.getUserId("superseded-token")).thenReturn(TEST_USER_ID);
        when(loginSupersedeMarker.isIssuedBeforeLastLogin(TEST_USER_ID, "superseded-token")).thenReturn(true);
        when(refreshTokenRepository.existsByUserId(TEST_USER_ID)).thenReturn(true);

        CustomException ex = assertThrows(CustomException.class, () -> authService.refresh(req));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
        verify(refreshTokenRevocationService, never()).revokeAll(anyString());
        verify(accessLogService, never()).log(anyString(), eq(kr.silverbridge.main.global.enums.AccessAction.TOKEN_REUSE_DETECTED));
    }

    @Test
    @DisplayName("refresh 자리에 access 토큰 → INVALID_TOKEN만, 아무것도 폐기하지 않고 재사용 로그도 없음 (AUTH-G27)")
    void refresh_access토큰_폐기없이_INVALID_TOKEN() {
        TokenRefreshRequest req = tokenRefreshRequest("access-token");
        when(refreshTokenRepository.findByToken("access-token")).thenReturn(Optional.empty());
        when(jwtTokenProvider.validateToken("access-token")).thenReturn(true);
        when(jwtTokenProvider.isRefreshToken("access-token")).thenReturn(false);

        CustomException ex = assertThrows(CustomException.class,
                () -> authService.refresh(req));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
        verify(refreshTokenRevocationService, never()).revokeAll(anyString());
        verify(refreshTokenRepository, never()).existsByUserId(anyString());
        verify(accessLogService, never()).log(anyString(), eq(kr.silverbridge.main.global.enums.AccessAction.TOKEN_REUSE_DETECTED));
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
        // REQUIRES_NEW 분리로 caller throw에도 폐기는 유지된다
        verify(refreshTokenRevocationService).revokeOne(expiredToken);
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
        when(req.getName()).thenReturn("홍길동");
        when(req.getPhone()).thenReturn(phone);
        when(req.getRole()).thenReturn(role);
        when(req.getVerificationNonce()).thenReturn("test-nonce");
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

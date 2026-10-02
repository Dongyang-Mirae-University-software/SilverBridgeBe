package kr.silverbridge.main.global.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import kr.silverbridge.main.global.util.RedisKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.http.HttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private FilterChain filterChain;

    private JwtTokenProvider jwtTokenProvider;
    private JwtAuthenticationFilter filter;

    private static final String USER_ID = "aB3x9Z";

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret("test-secret-key-at-least-256-bits-long-for-hmac-sha256-algorithm");
        props.setAccessTokenExpiration(30 * 60 * 1000L);
        props.setRefreshTokenExpiration(7 * 24 * 60 * 60 * 1000L);
        jwtTokenProvider = new JwtTokenProvider(props);
        filter = new JwtAuthenticationFilter(jwtTokenProvider, redisTemplate, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("정상 Access Token → SecurityContext 인증 등록 + 필터 체인 진행")
    void validAccessTokenAuthenticates() throws Exception {
        String access = jwtTokenProvider.generateAccessToken(USER_ID, "user@example.com", "WARD");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + access);
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(redisTemplate.hasKey(anyString())).thenReturn(false);          // 로그아웃 블랙리스트 아님
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);            // 비번 변경 무효화 아님

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(USER_ID);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Refresh Token을 Bearer로 제시 → 401 차단, 인증 미등록, 체인 미진행 (A-H1 회귀)")
    void refreshTokenAsBearerIsRejected() throws Exception {
        String refresh = jwtTokenProvider.generateRefreshToken(USER_ID);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + refresh);
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        // refresh token은 access로 인정되지 않아 401로 차단되고 인증이 등록되지 않는다
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("로그아웃(블랙리스트) 등록된 토큰 → 401 차단, 체인 미진행")
    void loggedOutTokenIsRejected() throws Exception {
        String access = jwtTokenProvider.generateAccessToken(USER_ID, "user@example.com", "WARD");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + access);
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(redisTemplate.hasKey(anyString())).thenReturn(true);           // 블랙리스트 등록됨

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("비밀번호 변경 시각 이후 발급되지 않은(이전) Access Token → 401 차단")
    void tokenIssuedBeforePasswordChangeIsRejected() throws Exception {
        String access = jwtTokenProvider.generateAccessToken(USER_ID, "user@example.com", "WARD");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + access);
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 토큰 발급 시각보다 미래의 무효화 시각 → iat <= invalidatedAt → 차단
        when(valueOperations.get(RedisKeys.PASSWORD_INVALIDATE + USER_ID))
                .thenReturn(String.valueOf(System.currentTimeMillis() + 60_000));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("토큰이 없으면 인증 없이 필터 체인만 진행 (permitAll 경로 등)")
    void noTokenProceedsAnonymously() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    // ─── 401 응답 code (P0 ApiResponse 형식) ─────────────────────────────

    @Test
    @DisplayName("필터가 내는 401 JSON에 code=LOGIN_REQUIRED와 기존 문구가 실린다")
    void unauthorizedBodyCarriesCode() throws Exception {
        MockHttpServletRequest request = bearer(access());
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"LOGIN_REQUIRED\"")
                .contains("로그인이 필요합니다.");
    }

    // ─── D2: 무효화 시각은 초 단위, 같은 초 발급은 허용 ─────────────────────

    @Test
    @DisplayName("무효화와 같은 초에 발급된 토큰은 통과한다 (AUTH-G06·USER-G13·XCUT-G10·ADMIN-G03)")
    void sameSecondTokenPasses() throws Exception {
        String access = access();
        long iatSec = jwtTokenProvider.getIssuedAt(access) / 1000;
        stubInvalidate(String.valueOf(iatSec));
        MockHttpServletRequest request = bearer(access);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("무효화보다 앞선 초에 발급된 토큰은 401")
    void earlierSecondTokenRejected() throws Exception {
        String access = access();
        long iatSec = jwtTokenProvider.getIssuedAt(access) / 1000;
        stubInvalidate(String.valueOf(iatSec + 1));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(bearer(access), response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("배포 전 ms로 저장된 무효화 값도 초로 환산해 같은 규칙으로 비교한다 (같은 초 통과·다음 초 거부)")
    void legacyMillisValueIsReadAsSeconds() throws Exception {
        String access = access();
        long iatMs = jwtTokenProvider.getIssuedAt(access);

        stubInvalidate(String.valueOf(iatMs + 999));    // 같은 초의 .999 → 통과
        MockHttpServletResponse sameSecond = new MockHttpServletResponse();
        filter.doFilterInternal(bearer(access), sameSecond, filterChain);
        assertThat(sameSecond.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();

        SecurityContextHolder.clearContext();
        stubInvalidate(String.valueOf(iatMs + 1000));   // 다음 초 → 거부
        MockHttpServletResponse nextSecond = new MockHttpServletResponse();
        filter.doFilterInternal(bearer(access), nextSecond, filterChain);
        assertThat(nextSecond.getStatus()).isEqualTo(401);
    }

    // ─── D1: Redis 오류 → 일반 503, SOS만 통과 ─────────────────────────────

    @Test
    @DisplayName("로그아웃 조회에서 Redis 오류 → 일반 경로는 503 + code=SERVICE_UNAVAILABLE, 체인 미진행 (XCUT-G03·AUTH-G21)")
    void logoutLookupFailureReturns503() throws Exception {
        MockHttpServletRequest request = bearer(access());
        request.setMethod("GET");
        request.setRequestURI("/api/user/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(redisTemplate.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("\"code\":\"SERVICE_UNAVAILABLE\"");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("무효화 값이 숫자가 아님(손상된 키) → 일반 경로는 401이 아니라 503")
    void malformedInvalidateValueReturns503() throws Exception {
        MockHttpServletRequest request = bearer(access());
        request.setMethod("GET");
        request.setRequestURI("/api/notice");
        MockHttpServletResponse response = new MockHttpServletResponse();
        stubInvalidate("not-a-number");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(503);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("SOS 발송(POST /api/ward/sos)은 Redis 오류여도 검사를 건너뛰고 인증 통과 (SOS-G10)")
    void sosPathFailsOpenOnRedisError() throws Exception {
        MockHttpServletRequest request = sosRequest(access());
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(redisTemplate.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(USER_ID);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("SOS 경로에서 무효화 값이 손상돼도 통과한다")
    void sosPathFailsOpenOnMalformedValue() throws Exception {
        MockHttpServletRequest request = sosRequest(access());
        MockHttpServletResponse response = new MockHttpServletResponse();
        stubInvalidate("{broken");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("SOS 경로라도 Redis가 정상이면 무효화 검사는 그대로 적용된다")
    void sosPathStillChecksWhenRedisHealthy() throws Exception {
        String access = access();
        MockHttpServletRequest request = sosRequest(access);
        MockHttpServletResponse response = new MockHttpServletResponse();
        stubInvalidate(String.valueOf(jwtTokenProvider.getIssuedAt(access) / 1000 + 1));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("SOS 경로 fail-open이어도 위조 토큰은 서명 검증에서 401 (서명·만료 검사는 유지)")
    void sosPathStillVerifiesSignature() throws Exception {
        MockHttpServletRequest request = sosRequest("forged.token.value");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(redisTemplate.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("SOS 설정(GET /api/ward/sos-setting)·GET /api/ward/sos 는 fail-open 대상이 아니다 → 503")
    void onlySosSendIsFailOpen() throws Exception {
        when(redisTemplate.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        MockHttpServletRequest setting = bearer(access());
        setting.setMethod("POST");
        setting.setRequestURI("/api/ward/sos-setting");
        MockHttpServletResponse r1 = new MockHttpServletResponse();
        filter.doFilterInternal(setting, r1, filterChain);
        assertThat(r1.getStatus()).isEqualTo(503);

        MockHttpServletRequest getSos = bearer(access());
        getSos.setMethod("GET");
        getSos.setRequestURI("/api/ward/sos");
        MockHttpServletResponse r2 = new MockHttpServletResponse();
        filter.doFilterInternal(getSos, r2, filterChain);
        assertThat(r2.getStatus()).isEqualTo(503);
    }

    private String access() {
        return jwtTokenProvider.generateAccessToken(USER_ID, "user@example.com", "WARD");
    }

    private MockHttpServletRequest bearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private MockHttpServletRequest sosRequest(String token) {
        MockHttpServletRequest request = bearer(token);
        request.setMethod("POST");
        request.setRequestURI("/api/ward/sos");
        return request;
    }

    private void stubInvalidate(String value) {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.PASSWORD_INVALIDATE + USER_ID)).thenReturn(value);
    }
}

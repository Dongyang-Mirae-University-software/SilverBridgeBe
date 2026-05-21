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
}

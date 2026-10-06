package kr.silverbridge.main.domain.auth.controller;

import jakarta.servlet.http.Cookie;
import kr.silverbridge.main.domain.auth.dto.KakaoLoginResponse;
import kr.silverbridge.main.domain.auth.dto.LoginResponse;
import kr.silverbridge.main.domain.auth.dto.TokenRefreshResponse;
import kr.silverbridge.main.domain.auth.service.AuthService;
import kr.silverbridge.main.domain.auth.service.KakaoAuthService;
import kr.silverbridge.main.domain.user.controller.UserController;
import kr.silverbridge.main.domain.user.service.UserService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.exception.GlobalExceptionHandler;
import kr.silverbridge.main.global.exception.TooManyRequestsException;
import kr.silverbridge.main.global.jwt.JwtProperties;
import kr.silverbridge.main.global.jwt.RefreshCookieManager;
import kr.silverbridge.main.global.jwt.RefreshCookieProperties;
import kr.silverbridge.main.global.security.RateLimitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * refresh 토큰 HttpOnly 쿠키 전환 (XCUT-G31) - 쿠키 속성·쿠키 갱신·본문 호환·만료·재사용 감지 유지.
 * 컨트롤러 + GlobalExceptionHandler를 실제 HTTP 흐름으로 태워 예외 응답에도 Set-Cookie가 실리는지 본다.
 */
@ExtendWith(MockitoExtension.class)
class RefreshCookieHttpTest {

    private static final String OLD_RT = "old-refresh-token";
    private static final String NEW_RT = "new-refresh-token";
    private static final String ALLOWED_ORIGIN = "https://devdmu.gosky.kr";

    @Mock private AuthService authService;
    @Mock private KakaoAuthService kakaoAuthService;
    @Mock private UserService userService;
    @Mock private RateLimitService rateLimitService;

    private RefreshCookieProperties props;

    @BeforeEach
    void setUp() {
        props = new RefreshCookieProperties();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("aB3x9Z", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockMvc mvc() {
        JwtProperties jwt = new JwtProperties();
        jwt.setRefreshTokenExpiration(604_800_000L);
        RefreshCookieManager manager = new RefreshCookieManager(props, jwt, ALLOWED_ORIGIN + ",http://localhost:3000");
        return MockMvcBuilders
                .standaloneSetup(new AuthController(authService, rateLimitService, manager),
                        new KakaoAuthController(kakaoAuthService, rateLimitService, manager),
                        new UserController(userService, manager))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    private static LoginResponse login(String refresh) {
        return LoginResponse.builder().accessToken("at").refreshToken(refresh)
                .userId("aB3x9Z").email("a@b.com").name("홍길동").role("GUARDIAN").build();
    }

    private static String setCookie(org.springframework.test.web.servlet.MvcResult r) {
        return r.getResponse().getHeader(HttpHeaders.SET_COOKIE);
    }

    private static final String LOGIN_JSON = "{\"email\":\"a@b.com\",\"password\":\"Passw0rd!x\"}";

    // ---------- 발급 ----------

    @Test
    @DisplayName("로그인: refresh 토큰이 HttpOnly·Secure·SameSite=Lax·Path=/api/auth·Max-Age=7일 쿠키로 내려가고 본문도 유지된다(전환 기간)")
    void loginIssuesHttpOnlyCookie() throws Exception {
        when(authService.login(any(), anyString(), any())).thenReturn(login(NEW_RT));

        var result = mvc().perform(post("/api/auth/signin").contentType(MediaType.APPLICATION_JSON).content(LOGIN_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("at"))
                .andExpect(jsonPath("$.data.refreshToken").value(NEW_RT))
                .andReturn();

        String header = setCookie(result);
        assertThat(header).startsWith("careai_rt=" + NEW_RT)
                .contains("HttpOnly").contains("Secure").contains("SameSite=Lax")
                .contains("Path=/api/auth").contains("Max-Age=604800")
                .doesNotContain("Domain=");
    }

    @Test
    @DisplayName("본문 호환을 끄면 본문 refreshToken은 null이고 쿠키만 내려간다")
    void bodyCompatOffRemovesBodyToken() throws Exception {
        props.setBodyCompat(false);
        when(authService.login(any(), anyString(), any())).thenReturn(login(NEW_RT));

        var result = mvc().perform(post("/api/auth/signin").contentType(MediaType.APPLICATION_JSON).content(LOGIN_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.data.accessToken").value("at"))
                .andReturn();

        assertThat(setCookie(result)).startsWith("careai_rt=" + NEW_RT).contains("HttpOnly");
    }

    @Test
    @DisplayName("쿠키 기능을 끄면(롤백) Set-Cookie 없이 기존처럼 본문으로만 내려간다")
    void disabledFallsBackToBodyOnly() throws Exception {
        props.setEnabled(false);
        props.setBodyCompat(false); // 꺼도 쿠키가 없으니 본문은 항상 유지된다
        when(authService.login(any(), anyString(), any())).thenReturn(login(NEW_RT));

        var result = mvc().perform(post("/api/auth/signin").contentType(MediaType.APPLICATION_JSON).content(LOGIN_JSON))
                .andExpect(jsonPath("$.data.refreshToken").value(NEW_RT))
                .andReturn();

        assertThat(setCookie(result)).isNull();
    }

    @Test
    @DisplayName("카카오 로그인(기존 회원)·가입 완료도 쿠키를 내리고, 신규 회원(토큰 없음)에는 내리지 않는다")
    void kakaoLoginAndRegisterIssueCookie() throws Exception {
        var existing = KakaoLoginResponse.builder().isNewUser(false).accessToken("at").refreshToken(NEW_RT)
                .userId("aB3x9Z").role("GUARDIAN").build();
        var newUser = KakaoLoginResponse.ofNewUser("1", "k@kakao.com", null, null, "pending");
        when(kakaoAuthService.kakaoLogin(any(), anyString(), any())).thenReturn(existing, newUser);
        when(kakaoAuthService.kakaoRegister(any(), anyString(), any())).thenReturn(login(NEW_RT));

        MockMvc mvc = mvc();
        var first = mvc.perform(post("/api/auth/signin/kakao").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"c\"}")).andExpect(status().isOk()).andReturn();
        assertThat(setCookie(first)).startsWith("careai_rt=" + NEW_RT).contains("HttpOnly");

        var second = mvc.perform(post("/api/auth/signin/kakao").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"c\"}")).andExpect(status().isOk()).andReturn();
        assertThat(setCookie(second)).isNull();

        var register = mvc.perform(post("/api/auth/signup/kakao").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kakaoId":"1","pendingToken":"p","name":"홍길동","phone":"01012345678","verificationNonce":"n",
                         "role":"GUARDIAN","gender":"MALE","birthDate":"1980-01-01","postcode":"12345",
                         "address":"서울","addressDetail":"1"}"""))
                .andReturn();
        assertThat(register.getResponse().getStatus()).isEqualTo(201);
        assertThat(setCookie(register)).startsWith("careai_rt=" + NEW_RT).contains("HttpOnly");
    }

    // ---------- 갱신 ----------

    @Test
    @DisplayName("쿠키만으로 갱신 성공: 서비스는 쿠키 토큰으로 호출되고 새 토큰이 쿠키로 회전된다")
    void refreshWithCookieOnly() throws Exception {
        when(authService.refresh(OLD_RT)).thenReturn(new TokenRefreshResponse("new-at", NEW_RT));

        var result = mvc().perform(post("/api/auth/refresh")
                        .cookie(new Cookie("careai_rt", OLD_RT)).header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("new-at"))
                .andReturn();

        assertThat(setCookie(result)).startsWith("careai_rt=" + NEW_RT).contains("HttpOnly").contains("Max-Age=604800");
    }

    @Test
    @DisplayName("Origin이 없는 요청(FE 중계 서버 등)도 쿠키 갱신이 된다")
    void refreshWithoutOriginAllowed() throws Exception {
        when(authService.refresh(OLD_RT)).thenReturn(new TokenRefreshResponse("new-at", NEW_RT));

        mvc().perform(post("/api/auth/refresh").cookie(new Cookie("careai_rt", OLD_RT)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("쿠키 갱신인데 Origin이 허용 목록에 없으면 403이고 서비스(회전)는 호출되지 않는다")
    void refreshWithForeignOriginRejected() throws Exception {
        var result = mvc().perform(post("/api/auth/refresh")
                        .cookie(new Cookie("careai_rt", OLD_RT)).header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isForbidden())
                .andReturn();

        verify(authService, never()).refresh(anyString());
        assertThat(setCookie(result)).isNull(); // 남의 사이트 요청이 사용자의 쿠키를 지우게 하지 않는다
    }

    @Test
    @DisplayName("본문 refreshToken(전환 기간)으로도 갱신된다 - 구버전 FE가 깨지지 않는다")
    void refreshWithBodyStillWorks() throws Exception {
        when(authService.refresh(OLD_RT)).thenReturn(new TokenRefreshResponse("new-at", NEW_RT));

        mvc().perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + OLD_RT + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refreshToken").value(NEW_RT));
    }

    @Test
    @DisplayName("본문과 쿠키가 모두 있으면 본문이 우선이다")
    void bodyWinsOverCookie() throws Exception {
        when(authService.refresh("body-token")).thenReturn(new TokenRefreshResponse("new-at", NEW_RT));

        mvc().perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .cookie(new Cookie("careai_rt", "cookie-token"))
                        .content("{\"refreshToken\":\"body-token\"}"))
                .andExpect(status().isOk());

        verify(authService).refresh("body-token");
        verify(authService, never()).refresh("cookie-token");
    }

    @Test
    @DisplayName("본문 호환을 끄면 본문 토큰은 무시된다 - 쿠키가 없으면 400")
    void bodyIgnoredWhenCompatOff() throws Exception {
        props.setBodyCompat(false);

        mvc().perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + OLD_RT + "\"}"))
                .andExpect(status().isBadRequest());

        verify(authService, never()).refresh(anyString());
    }

    @Test
    @DisplayName("쿠키도 본문도 없으면 400")
    void refreshWithoutAnyTokenIs400() throws Exception {
        mvc().perform(post("/api/auth/refresh")).andExpect(status().isBadRequest());
        mvc().perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("재사용 감지로 거절(401)되면 쿠키를 만료시켜 죽은 쿠키를 남기지 않는다")
    void deadTokenClearsCookie() throws Exception {
        when(authService.refresh(OLD_RT)).thenThrow(new CustomException(ErrorCode.INVALID_TOKEN));

        var result = mvc().perform(post("/api/auth/refresh").cookie(new Cookie("careai_rt", OLD_RT)))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(setCookie(result)).startsWith("careai_rt=;").contains("Max-Age=0")
                .contains("HttpOnly").contains("Path=/api/auth");
    }

    @Test
    @DisplayName("이용 제한(403) 계정의 갱신도 쿠키를 만료시킨다")
    void restrictedAccountClearsCookie() throws Exception {
        when(authService.refresh(OLD_RT)).thenThrow(new CustomException(ErrorCode.INACTIVE_USER));

        var result = mvc().perform(post("/api/auth/refresh").cookie(new Cookie("careai_rt", OLD_RT)))
                .andExpect(status().isForbidden())
                .andReturn();

        assertThat(setCookie(result)).contains("Max-Age=0");
    }

    @Test
    @DisplayName("속도 제한(429) 같은 일시 오류에는 쿠키를 지우지 않는다")
    void transientErrorKeepsCookie() throws Exception {
        doThrow(new TooManyRequestsException(10L)).when(rateLimitService).check(anyString(), anyString());

        var result = mvc().perform(post("/api/auth/refresh").cookie(new Cookie("careai_rt", OLD_RT)))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        assertThat(setCookie(result)).isNull();
        verify(authService, never()).refresh(anyString());
    }

    // ---------- 만료 ----------

    @Test
    @DisplayName("로그아웃: 같은 속성의 Max-Age=0 Set-Cookie로 쿠키를 지운다")
    void logoutClearsCookie() throws Exception {
        var result = mvc().perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer at"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(setCookie(result)).startsWith("careai_rt=;").contains("Max-Age=0")
                .contains("HttpOnly").contains("Secure").contains("SameSite=Lax").contains("Path=/api/auth");
        verify(authService).logout(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("비밀번호 변경·회원 탈퇴 성공 시에도 refresh 쿠키를 만료시킨다")
    void passwordChangeAndWithdrawClearCookie() throws Exception {
        MockMvc mvc = mvc();

        var pw = mvc.perform(put("/api/user/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Passw0rd!x\",\"newPassword\":\"Newpass1!y\"}"))
                .andReturn();
        assertThat(pw.getResponse().getStatus()).isEqualTo(200);
        assertThat(setCookie(pw)).contains("Max-Age=0").contains("HttpOnly");

        var wd = mvc.perform(delete("/api/user/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"Passw0rd!x\"}"))
                .andReturn();
        assertThat(wd.getResponse().getStatus()).isEqualTo(200);
        assertThat(setCookie(wd)).contains("Max-Age=0").contains("HttpOnly");
    }
}

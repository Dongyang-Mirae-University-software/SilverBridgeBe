package kr.silverbridge.main.global.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.global.exception.TooManyRequestsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UserRateLimitFilterTest {

    private static final int LIMIT = 600;

    @Mock
    private RateLimitService rateLimitService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private UserRateLimitFilter filter(boolean enabled) {
        return new UserRateLimitFilter(rateLimitService, objectMapper, enabled, LIMIT);
    }

    private void login(String userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_GUARDIAN"))));
    }

    private MockFilterChain run(UserRateLimitFilter f, String method, String uri, MockHttpServletResponse response)
            throws Exception {
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(new MockHttpServletRequest(method, uri), response, chain);
        return chain;
    }

    @Test
    @DisplayName("로그인 사용자의 일반 요청은 사용자 ID 기준으로 분당 600회까지 검사하고 통과한다")
    void normalRequestChecksByUserId() throws Exception {
        login("user-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = run(filter(true), "GET", "/api/guardian/medication", response);

        verify(rateLimitService).check("user-api", "user-1", LIMIT);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("초과하면 429 + code TOO_MANY_REQUESTS + Retry-After 헤더 + data.retryAfterSeconds, 요청은 컨트롤러로 가지 않는다")
    void exceededReturns429InExistingFormat() throws Exception {
        login("user-1");
        doThrow(new TooManyRequestsException(42)).when(rateLimitService).check("user-api", "user-1", LIMIT);
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = run(filter(true), "GET", "/api/guardian/medication", response);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("42");
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("code").asText()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(body.get("data").get("retryAfterSeconds").asLong()).isEqualTo(42);
        assertThat(chain.getRequest()).isNull();
    }

    @ParameterizedTest(name = "{0} {1} 는 제한하지 않는다")
    @CsvSource({
            "POST, /api/ward/sos",
            "POST, /api/ward/sos/",
            "GET,  /api/ward/sos-setting",
            "PUT,  /api/ward/sos-setting",
            "GET,  /api/guardian/sos/history",
            "GET,  /api/guardian/sos",
            "POST, /api/auth/login",
            "POST, /api/auth/refresh",
            "POST, /api/auth/logout",
            "GET,  /ws/info",
            "OPTIONS, /api/guardian/medication"
    })
    @DisplayName("SOS·인증·WebSocket·preflight 는 제한 검사 자체를 하지 않는다")
    void exemptPathsNeverChecked(String method, String uri) throws Exception {
        login("user-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = run(filter(true), method, uri, response);

        verifyNoInteractions(rateLimitService);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @ParameterizedTest(name = "{0} 는 제외 대상이 아니다(이름만 비슷한 경로)")
    @ValueSource(strings = {"/api/ward/sosx", "/api/ward/medication/sos", "/api/guardian/anomaly", "/api/user/me"})
    @DisplayName("sos 로 시작하는 구간이 아니면 제한한다 - 제외 규칙이 과하게 넓지 않다")
    void similarPathsAreLimited(String uri) throws Exception {
        login("user-1");

        run(filter(true), "GET", uri, new MockHttpServletResponse());

        verify(rateLimitService).check("user-api", "user-1", LIMIT);
    }

    @Test
    @DisplayName("미인증·익명 요청은 사용자 ID 가 없어 검사하지 않는다")
    void unauthenticatedSkipped() throws Exception {
        run(filter(true), "GET", "/api/guardian/medication", new MockHttpServletResponse());
        verifyNoInteractions(rateLimitService);

        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        run(filter(true), "GET", "/api/guardian/medication", new MockHttpServletResponse());
        verifyNoInteractions(rateLimitService);
    }

    @Test
    @DisplayName("킬 스위치가 꺼져 있으면 검사하지 않는다")
    void disabledSkipsCheck() throws Exception {
        login("user-1");

        MockFilterChain chain = run(filter(false), "GET", "/api/guardian/medication", new MockHttpServletResponse());

        verify(rateLimitService, never()).check(anyString(), anyString(), anyInt());
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("검사 중 예기치 않은 오류가 나도 요청은 막지 않는다 (fail-open)")
    void unexpectedErrorFailsOpen() throws Exception {
        login("user-1");
        doThrow(new IllegalStateException("boom")).when(rateLimitService).check("user-api", "user-1", LIMIT);
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = run(filter(true), "GET", "/api/guardian/medication", response);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("컨텍스트 경로가 붙어도 SOS 경로를 정확히 제외한다")
    void contextPathStripped() throws Exception {
        login("user-1");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/app/api/ward/sos");
        request.setContextPath("/app");

        filter(true).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verifyNoInteractions(rateLimitService);
    }
}

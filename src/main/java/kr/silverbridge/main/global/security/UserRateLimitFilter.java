package kr.silverbridge.main.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.exception.TooManyRequestsException;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 로그인한 사용자 기준 요청 횟수 제한 (2026-10-06).
 *
 * <p>IP 기준 제한은 로그인·인증 경로에만 있어, 토큰 하나로 읽기 API를 초당 약 1,000건 보낼 수 있었다(10/5 부하 측정).
 * {@link kr.silverbridge.main.global.jwt.JwtAuthenticationFilter} 뒤에서 인증된 사용자 ID 로 1분 고정 윈도우
 * {@code maxPerMinute}회까지만 허용한다. 기준값은 정상 사용(새로고침 한 번에 수십 건, 실시간 알림 뒤 재조회,
 * 여러 탭)이 막히지 않을 만큼 넉넉하게 잡는다.
 *
 * <p><b>제외(절대 막지 않음)</b>
 * <ul>
 *   <li>SOS 관련 경로 - {@code /api/ward/sos}, {@code /api/ward/sos-setting}, {@code /api/guardian/sos/**} 등
 *       ({@link #SOS_PATH}). SOS 는 어떤 제한에도 막히면 안 되는 필수 경로다(정책 그대로).</li>
 *   <li>{@code /api/auth/**} - 로그인·갱신·로그아웃은 이미 IP 기준 제한이 있어 기존 규칙을 그대로 둔다.</li>
 *   <li>{@code /ws/**} - WebSocket 핸드셰이크(연결 1회). {@code OPTIONS} preflight.</li>
 *   <li>미인증 요청 - 사용자 ID 가 없다.</li>
 * </ul>
 *
 * <p><b>Redis 장애</b>: {@link RateLimitService} 가 fail-open(통과 + WARN)이라 그대로 따른다. 그 밖의 예기치 않은
 * 오류도 통과시킨다 - 제한은 보조 방어라 요청을 막는 쪽으로 실패하지 않는다.
 *
 * <p>초과 응답은 기존 {@code GlobalExceptionHandler} 와 같은 형식이다(429, code {@code TOO_MANY_REQUESTS},
 * {@code Retry-After}, {@code data.retryAfterSeconds}). 필터 안이라 핸들러를 못 타서 직접 쓴다.
 */
@Slf4j
public class UserRateLimitFilter extends OncePerRequestFilter {

    static final String ENDPOINT = "user-api";

    // /api/ward/sos, /api/ward/sos/..., /api/ward/sos-setting, /api/guardian/sos/history 등 - 구간 이름이 sos 로 시작하면 제외
    private static final Pattern SOS_PATH = Pattern.compile("^/api/[^/]+/sos([/-].*)?$");

    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int maxPerMinute;

    public UserRateLimitFilter(RateLimitService rateLimitService, ObjectMapper objectMapper,
                               boolean enabled, int maxPerMinute) {
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.maxPerMinute = maxPerMinute;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String userId = enabled && !isExempt(request) ? authenticatedUserId() : null;
        if (userId != null) {
            try {
                rateLimitService.check(ENDPOINT, userId, maxPerMinute);
            } catch (TooManyRequestsException e) {
                log.warn("[USER-RATE-LIMIT] 사용자 요청 제한 초과 retryAfter={}s", e.getRetryAfterSeconds());
                writeTooManyRequests(response, e);
                return;
            } catch (RuntimeException e) {
                // RateLimitService 가 Redis 오류는 이미 삼킨다. 그 밖의 오류도 요청을 막지 않는다.
                log.warn("[USER-RATE-LIMIT-ERROR] 제한 검사 생략(fail-open) error={}", e.getClass().getSimpleName());
            }
        }
        filterChain.doFilter(request, response);
    }

    /** 컨텍스트 경로를 뗀 경로로 제외 대상인지 판단한다. */
    static boolean isExempt(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getRequestURI();
        if (path == null) return true;
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.equals("/api/auth") || path.startsWith("/api/auth/")
                || path.equals("/ws") || path.startsWith("/ws/")
                || SOS_PATH.matcher(path).matches();
    }

    private String authenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        String name = authentication.getName();
        return (name == null || name.isBlank()) ? null : name;
    }

    private void writeTooManyRequests(HttpServletResponse response, TooManyRequestsException e) throws IOException {
        ErrorCode errorCode = e.getErrorCode();
        response.setStatus(errorCode.getStatus().value());
        response.setHeader("Retry-After", String.valueOf(e.getRetryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.failWithData(errorCode, Map.of("retryAfterSeconds", e.getRetryAfterSeconds()))));
    }
}

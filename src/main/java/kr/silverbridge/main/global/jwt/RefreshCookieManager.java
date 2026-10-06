package kr.silverbridge.main.global.jwt;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * refresh 토큰 쿠키의 발급·만료·읽기 (XCUT-G31).
 * 쿠키 속성(HttpOnly 고정)과 전환 기간 호환(본문 refreshToken)을 한 곳에서 정한다.
 */
@Component
public class RefreshCookieManager {

    private final RefreshCookieProperties props;
    private final Duration maxAge;
    private final List<String> allowedOrigins;

    public RefreshCookieManager(RefreshCookieProperties props,
                                JwtProperties jwtProperties,
                                @Value("${app.cors.allowed-origins}") String allowedOrigins) {
        this.props = props;
        this.maxAge = Duration.ofMillis(jwtProperties.getRefreshTokenExpiration());
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 새 refresh 토큰을 HttpOnly 쿠키로 내린다. 비활성이면 아무것도 하지 않는다. */
    public void issue(HttpServletResponse response, String refreshToken) {
        if (!props.isEnabled() || refreshToken == null) {
            return;
        }
        response.addHeader(HttpHeaders.SET_COOKIE, build(refreshToken, maxAge).toString());
    }

    /** 쿠키를 즉시 만료시킨다(Max-Age=0). 속성은 발급 때와 같아야 브라우저가 같은 쿠키로 인식한다. */
    public void clear(HttpServletResponse response) {
        if (!props.isEnabled()) {
            return;
        }
        response.addHeader(HttpHeaders.SET_COOKIE, build("", Duration.ZERO).toString());
    }

    /** 응답 본문에 싣는 refreshToken. 본문 호환이 꺼져 있으면(쿠키 전용) null. */
    public String bodyValue(String refreshToken) {
        return bodyCompatActive() ? refreshToken : null;
    }

    /** 요청 쿠키의 refresh 토큰(없으면 empty). */
    public Optional<String> fromCookie(HttpServletRequest request) {
        if (!props.isEnabled() || request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> props.getName().equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    /** 요청 본문의 refresh 토큰. 본문 호환이 꺼져 있으면 무시한다. */
    public Optional<String> fromBody(String bodyToken) {
        if (!bodyCompatActive() || bodyToken == null || bodyToken.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(bodyToken);
    }

    /**
     * 쿠키로 갱신할 때의 CSRF 방어선(SameSite=Lax와 함께). Origin 헤더가 있는데 허용 목록에 없으면 거부한다.
     * Origin이 없는 요청(서버 간 호출·FE 중계 서버)은 통과시킨다 - 브라우저의 교차 출처 POST는 항상 Origin을 싣는다.
     */
    public void verifyOrigin(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !allowedOrigins.contains(origin)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
    }

    private boolean bodyCompatActive() {
        // 쿠키를 끄면(롤백) 본문이 유일한 전달 경로이므로 호환도 함께 켠다
        return props.isBodyCompat() || !props.isEnabled();
    }

    private ResponseCookie build(String value, Duration age) {
        return ResponseCookie.from(props.getName(), value)
                .httpOnly(true)
                .secure(props.isSecure())
                .sameSite(props.getSameSite())
                .path(props.getPath())
                .maxAge(age)
                .build();
    }
}

package kr.silverbridge.main.global.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * 신뢰 가능한 클라이언트 IP 해석.
 *
 * <p>RateLimit·로그인 잠금·보안 로깅의 식별자로 쓰는 클라이언트 IP는 위조 불가능해야 한다.
 * 본 서비스는 nginx 뒤에 있고({@code server.forward-headers-strategy: framework}), nginx는
 * 모든 server 블록에서 {@code proxy_set_header X-Real-IP $remote_addr}로 실제 TCP 피어 IP를
 * <b>덮어써</b> 전달한다(클라이언트가 보낸 X-Real-IP는 폐기 → 위조 불가).
 *
 * <p>반면 {@code X-Forwarded-For}는 {@code $proxy_add_x_forwarded_for}로 <b>append</b>되어
 * 클라이언트가 보낸 선두값이 그대로 남고, {@code forward-headers-strategy: framework} 때문에
 * {@link HttpServletRequest#getRemoteAddr()}가 그 선두값(스푸핑 가능)을 반환한다. 따라서
 * RateLimit 키를 {@code getRemoteAddr()}에만 의존하면 헤더 회전으로 우회된다(SPOT-H1, 2026-05-23).
 *
 * <p>이에 클라이언트 IP는 nginx가 보장하는 {@code X-Real-IP}를 우선 신뢰하고, 헤더가 없을 때만
 * (로컬 개발·nginx 미경유) {@code getRemoteAddr()}로 폴백한다.
 */
public final class ClientIpResolver {

    private ClientIpResolver() {}

    /** nginx가 $remote_addr로 덮어써 주는, 클라이언트 위조 불가 헤더 */
    private static final String X_REAL_IP = "X-Real-IP";

    /** 요청/IP를 알 수 없을 때의 대체 값 (로그·키 안전용) */
    private static final String UNKNOWN = "-";

    /**
     * 신뢰 가능한 클라이언트 IP를 반환한다.
     * <p>X-Real-IP(nginx가 세팅, 위조 불가) 우선, 없으면 {@code getRemoteAddr()} 폴백.
     *
     * @param request 현재 요청 (null이면 {@code "-"})
     * @return 클라이언트 IP 문자열
     */
    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        String realIp = request.getHeader(X_REAL_IP);
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}

package kr.silverbridge.main.global.util;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

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
 * <p><b>신뢰 프록시 경유 (2026-10-02, AUTH-G28·FEUX-G07·XCUT-G02)</b>: 브라우저 → FE 서버(BFF) → nginx →
 * 백엔드 구조에서는 nginx가 보는 피어가 FE 서버라 X-Real-IP가 전 사용자 공통 값이 되고, 속도제한이
 * 사이트 전체로 합산됐다(외부인 한 명이 분당 11회 signin만 보내도 전원 429). 그래서
 * <ol>
 *   <li>피어 = X-Real-IP(없으면 컨테이너가 본 실제 TCP 피어 — framework 전략이 바꾼 값이 아니다)</li>
 *   <li>피어가 신뢰 프록시({@code app.client-ip.trusted-proxies})가 <b>아니면</b> 피어를 그대로 쓴다(기존 동작)</li>
 *   <li>신뢰 프록시면 {@code X-Forwarded-For}를 <b>오른쪽부터</b> 읽어 처음 나오는 신뢰 대상이 아닌 주소를 쓴다.
 *       왼쪽 값은 클라이언트가 마음대로 넣을 수 있으므로 앞에서부터 읽지 말 것.</li>
 *   <li>형식이 깨진 값·빈 값을 만나거나 끝까지 신뢰 대상뿐이면 피어로 되돌아간다(안전 쪽).</li>
 * </ol>
 * 신뢰 목록은 기동 시 {@link #configureTrustedProxies(List)}로 한 번 주입된다(미주입 = 빈 목록 = 기존 동작).
 */
public final class ClientIpResolver {

    private ClientIpResolver() {}

    /** nginx가 $remote_addr로 덮어써 주는, 클라이언트 위조 불가 헤더 */
    private static final String X_REAL_IP = "X-Real-IP";

    /** 프록시마다 오른쪽에 append되는 전달 헤더 — 신뢰 프록시가 보낸 경우에만 해석한다 */
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    /** 요청/IP를 알 수 없을 때의 대체 값 (로그·키 안전용) */
    private static final String UNKNOWN = "-";

    /** 전달 헤더가 비정상적으로 길면 해석하지 않는다(홉 수 상한) */
    private static final int MAX_FORWARDED_HOPS = 20;

    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");

    /** IPv6 리터럴 후보 문자만 허용 — 호스트명이 섞이면 DNS 조회가 일어나므로 미리 거른다 */
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private static volatile List<Cidr> trustedProxies = Collections.emptyList();

    /**
     * 신뢰 프록시 목록(IP 또는 CIDR)을 설정한다. 기동 시 한 번 호출된다.
     *
     * @throws IllegalArgumentException 형식이 잘못된 항목이 있으면(기동 실패 — fail-fast)
     */
    public static void configureTrustedProxies(List<String> entries) {
        List<Cidr> parsed = new ArrayList<>();
        if (entries != null) {
            for (String entry : entries) {
                if (StringUtils.hasText(entry)) {
                    parsed.add(Cidr.parse(entry.trim()));
                }
            }
        }
        trustedProxies = List.copyOf(parsed);
    }

    /**
     * 신뢰 가능한 클라이언트 IP를 반환한다.
     *
     * @param request 현재 요청 (null이면 {@code "-"})
     * @return 클라이언트 IP 문자열
     */
    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        String realIp = request.getHeader(X_REAL_IP);
        String peer = StringUtils.hasText(realIp) ? realIp.trim() : directPeerAddr(request);
        if (peer == null) {
            return UNKNOWN;
        }

        List<Cidr> trusted = trustedProxies;
        if (trusted.isEmpty() || !isTrusted(parseLiteral(peer), trusted)) {
            // 신뢰 대상이 아닌 피어가 보낸 전달 헤더는 위조일 수 있어 보지 않는다
            return peer;
        }
        return fromForwardedFor(request, trusted, peer);
    }

    /** X-Forwarded-For를 오른쪽부터 읽어 처음 나오는 비신뢰 주소. 못 찾으면 peer. */
    private static String fromForwardedFor(HttpServletRequest request, List<Cidr> trusted, String peer) {
        List<String> hops = forwardedHops(request);
        if (hops.isEmpty() || hops.size() > MAX_FORWARDED_HOPS) {
            return peer;
        }
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = hops.get(i).trim();
            InetAddress address = parseLiteral(hop);
            if (address == null) {
                // 형식이 깨진 값 너머는 누가 썼는지 알 수 없다 — 피어로 되돌아간다
                return peer;
            }
            if (!isTrusted(address, trusted)) {
                return hop;
            }
        }
        return peer;
    }

    /**
     * 전달 헤더도 래퍼를 벗긴 원래 요청에서 읽는다. ForwardedHeaderFilter가 감싼 요청은
     * X-Forwarded-* 헤더를 숨기므로, 감싼 채 읽으면 항상 비어 피어로 되돌아간다(2026-10-05).
     * 신뢰 여부는 위에서 피어로 이미 판정했으므로 원본 헤더를 읽어도 위조 방어는 그대로다.
     */
    private static List<String> forwardedHops(HttpServletRequest request) {
        ServletRequest original = unwrap(request);
        List<String> hops = new ArrayList<>();
        if (original instanceof HttpServletRequest http) {
            Enumeration<String> headers = http.getHeaders(X_FORWARDED_FOR);
            if (headers != null) {
                while (headers.hasMoreElements()) {
                    addHops(hops, headers.nextElement());
                }
            } else {
                addHops(hops, http.getHeader(X_FORWARDED_FOR));
            }
        }
        return hops;
    }

    private static void addHops(List<String> hops, String headerValue) {
        if (headerValue == null) {
            return;
        }
        // split(",", -1): 끝의 빈 값도 남겨 "형식 오류"로 다룬다
        Collections.addAll(hops, headerValue.split(",", -1));
    }

    /**
     * 컨테이너가 본 실제 TCP 피어. framework 전략의 ForwardedHeaderFilter가 감싼 요청은
     * getRemoteAddr()를 XFF 선두값(위조 가능)으로 바꾸므로 래퍼를 벗겨 원래 값을 읽는다.
     */
    private static String directPeerAddr(HttpServletRequest request) {
        String addr = unwrap(request).getRemoteAddr();
        return StringUtils.hasText(addr) ? addr.trim() : null;
    }

    /** 요청 래퍼를 끝까지 벗긴 원래(컨테이너) 요청 */
    private static ServletRequest unwrap(ServletRequest request) {
        ServletRequest current = request;
        while (current instanceof ServletRequestWrapper wrapper) {
            current = wrapper.getRequest();
        }
        return current;
    }

    private static boolean isTrusted(InetAddress address, List<Cidr> trusted) {
        if (address == null) {
            return false;
        }
        for (Cidr cidr : trusted) {
            if (cidr.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /** IP 리터럴만 파싱한다(호스트명·포트·대괄호·zone id는 형식 오류로 null). DNS 조회 없음. */
    static InetAddress parseLiteral(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return null;
        }
        boolean v6 = text.indexOf(':') >= 0;
        if (v6 ? !IPV6_CHARS.matcher(text).matches() : !IPV4.matcher(text).matches()) {
            return null;
        }
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** IP 대역(CIDR). 접두 길이가 없으면 단일 주소. IPv4-mapped IPv6는 JDK가 IPv4로 정규화한다. */
    private record Cidr(byte[] network, int prefixLength) {

        static Cidr parse(String entry) {
            int slash = entry.indexOf('/');
            String addressPart = slash < 0 ? entry : entry.substring(0, slash);
            InetAddress address = parseLiteral(addressPart);
            if (address == null) {
                throw new IllegalArgumentException("app.client-ip.trusted-proxies 형식 오류: " + entry);
            }
            byte[] bytes = address.getAddress();
            int maxPrefix = bytes.length * 8;
            int prefix = maxPrefix;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(entry.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("app.client-ip.trusted-proxies 형식 오류: " + entry);
                }
                if (prefix < 0 || prefix > maxPrefix) {
                    throw new IllegalArgumentException("app.client-ip.trusted-proxies 접두 길이 오류: " + entry);
                }
            }
            return new Cidr(bytes, prefix);
        }

        boolean contains(InetAddress address) {
            byte[] target = address.getAddress();
            if (target.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (target[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (target[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}

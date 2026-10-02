package kr.silverbridge.main.global.util;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ClientIpResolver} 검증 — X-Real-IP(nginx 세팅, 위조 불가) 우선,
 * 없을 때만 getRemoteAddr() 폴백 (SPOT-H1, 2026-05-23).
 */
@DisplayName("ClientIpResolver")
class ClientIpResolverTest {

    @Test
    @DisplayName("X-Real-IP가 있으면 그 값을 사용한다 (getRemoteAddr 무시 — XFF 스푸핑 우회 차단)")
    void X_Real_IP_우선() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Real-IP")).thenReturn("203.0.113.7");
        // getRemoteAddr()는 framework 전략에서 XFF 선두(스푸핑 가능)값일 수 있음 — 무시되어야 함
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("X-Real-IP 앞뒤 공백은 제거한다")
    void X_Real_IP_트림() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Real-IP")).thenReturn("  203.0.113.7  ");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("X-Real-IP가 없으면(null) getRemoteAddr()로 폴백한다")
    void X_Real_IP_없으면_폴백() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Real-IP")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("X-Real-IP가 빈 문자열이면 getRemoteAddr()로 폴백한다")
    void X_Real_IP_공백이면_폴백() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Real-IP")).thenReturn("   ");
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("10.0.0.5");
    }

    @Test
    @DisplayName("request가 null이면 \"-\"를 반환한다")
    void request_null이면_대체값() {
        assertThat(ClientIpResolver.resolve(null)).isEqualTo("-");
    }

    // ─── 신뢰 프록시 경유 (2026-10-02, AUTH-G28·FEUX-G07·XCUT-G02) ──────────────

    /** FE 서버(BFF) 공인 IP 예시 + 사설망 + IPv6 대역 */
    private static final List<String> TRUSTED = List.of(
            "198.51.100.10", "127.0.0.0/8", "::1", "10.0.0.0/8", "172.16.0.0/12", "2001:db8:ffff::/48");

    @AfterEach
    void resetTrustedProxies() {
        ClientIpResolver.configureTrustedProxies(List.of());
    }

    private static MockHttpServletRequest request(String realIp, String remoteAddr, String xff) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (!"-".equals(realIp)) {
            request.addHeader("X-Real-IP", realIp);
        }
        if (!"-".equals(xff)) {
            request.addHeader("X-Forwarded-For", xff);
        }
        return request;
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            // 설명 | X-Real-IP | remoteAddr | X-Forwarded-For | 기대값
            "비신뢰 피어(브라우저 직결) - XFF 위조 무시, X-Real-IP 그대로      | 203.0.113.7   | 172.18.0.1 | 6.6.6.6                          | 203.0.113.7",
            "신뢰 피어(FE 서버) - XFF 마지막 비신뢰 주소                      | 198.51.100.10 | 172.18.0.1 | 203.0.113.7, 198.51.100.10       | 203.0.113.7",
            "신뢰 피어 - 위조 prepend는 왼쪽이라 무시                         | 198.51.100.10 | 172.18.0.1 | 6.6.6.6, 203.0.113.7, 198.51.100.10 | 203.0.113.7",
            "신뢰 피어 - 다중 홉(사설망 프록시 여럿) 건너뜀                   | 198.51.100.10 | 172.18.0.1 | 203.0.113.7, 10.0.0.3, 172.20.0.2 | 203.0.113.7",
            "신뢰 피어 - XFF 없음 → 피어                                      | 198.51.100.10 | 172.18.0.1 | -                                | 198.51.100.10",
            "신뢰 피어 - XFF 빈 값 → 피어                                     | 198.51.100.10 | 172.18.0.1 | ' '                              | 198.51.100.10",
            "신뢰 피어 - 끝까지 신뢰 대상뿐 → 피어                            | 198.51.100.10 | 172.18.0.1 | 10.0.0.9, 127.0.0.1              | 198.51.100.10",
            "신뢰 피어 - 오른쪽 값 형식 오류 → 피어(그 너머는 믿지 않음)      | 198.51.100.10 | 172.18.0.1 | 203.0.113.7, garbage             | 198.51.100.10",
            "신뢰 피어 - 호스트명은 형식 오류(DNS 조회 안 함)                 | 198.51.100.10 | 172.18.0.1 | evil.example.com                 | 198.51.100.10",
            "신뢰 피어 - 포트 붙은 값은 형식 오류                             | 198.51.100.10 | 172.18.0.1 | 203.0.113.7:5555                 | 198.51.100.10",
            "신뢰 피어 - 끝의 빈 항목은 형식 오류                             | 198.51.100.10 | 172.18.0.1 | '203.0.113.7,'                   | 198.51.100.10",
            "신뢰 피어 - 범위 밖 옥텟은 형식 오류                             | 198.51.100.10 | 172.18.0.1 | 203.0.113.256                    | 198.51.100.10",
            "신뢰 피어 - IPv6 사용자 주소                                     | 198.51.100.10 | 172.18.0.1 | '6.6.6.6, 2001:db8:1::7'         | 2001:db8:1::7",
            "IPv6 신뢰 피어 - IPv6 대역 안 프록시 건너뜀                      | 2001:db8:ffff::1 | 172.18.0.1 | '203.0.113.7, 2001:db8:ffff::2' | 203.0.113.7",
            "IPv6 비신뢰 피어 - XFF 무시                                      | 2001:db8:1::1 | 172.18.0.1 | 6.6.6.6                          | 2001:db8:1::1",
            "IPv4-mapped IPv6 피어도 IPv4 대역으로 판정                       | '::ffff:198.51.100.10' | 172.18.0.1 | 203.0.113.7     | 203.0.113.7",
            "X-Real-IP 없음 + 루프백 직결(로컬 개발) - XFF 해석               | -             | 127.0.0.1  | 203.0.113.7                      | 203.0.113.7",
            "X-Real-IP 없음 + 비신뢰 직결 - XFF 무시                          | -             | 203.0.113.9 | 6.6.6.6                         | 203.0.113.9",
            "X-Real-IP 형식 오류 - 신뢰 판정 안 함, 기존처럼 그대로           | garbage       | 127.0.0.1  | 6.6.6.6                          | garbage",
    })
    @DisplayName("신뢰/비신뢰 피어 × X-Forwarded-For 위조 시나리오")
    void 신뢰_프록시_시나리오(String description, String realIp, String remoteAddr, String xff, String expected) {
        ClientIpResolver.configureTrustedProxies(TRUSTED);

        assertThat(ClientIpResolver.resolve(request(realIp, remoteAddr, xff))).isEqualTo(expected);
    }

    @Test
    @DisplayName("XFF 헤더가 여러 줄이면 이어 붙여 오른쪽부터 읽는다")
    void 여러_줄_XFF() {
        ClientIpResolver.configureTrustedProxies(TRUSTED);
        MockHttpServletRequest request = request("198.51.100.10", "172.18.0.1", "6.6.6.6");
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("신뢰 목록이 비어 있으면(미설정) 기존 동작 - XFF를 보지 않는다")
    void 신뢰_목록_없으면_기존_동작() {
        assertThat(ClientIpResolver.resolve(request("127.0.0.1", "127.0.0.1", "203.0.113.7")))
                .isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("X-Real-IP가 없으면 framework 전략 래퍼가 바꾼 값이 아니라 실제 TCP 피어를 쓴다")
    void 래퍼를_벗겨_실제_피어() {
        MockHttpServletRequest raw = request("-", "203.0.113.9", "-");
        // ForwardedHeaderFilter처럼 getRemoteAddr()를 XFF 선두(위조 가능)값으로 바꾸는 래퍼
        HttpServletRequest wrapped = new HttpServletRequestWrapper(raw) {
            @Override
            public String getRemoteAddr() {
                return "6.6.6.6";
            }
        };

        assertThat(ClientIpResolver.resolve(wrapped)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("홉이 비정상적으로 많으면 해석하지 않고 피어를 쓴다")
    void 홉_상한() {
        ClientIpResolver.configureTrustedProxies(TRUSTED);
        String xff = String.join(", ", java.util.Collections.nCopies(21, "203.0.113.7"));

        assertThat(ClientIpResolver.resolve(request("198.51.100.10", "172.18.0.1", xff)))
                .isEqualTo("198.51.100.10");
    }

    @ParameterizedTest
    @CsvSource({"not-an-ip", "10.0.0.0/33", "::1/129", "10.0.0.0/x", "example.com"})
    @DisplayName("신뢰 목록 형식 오류는 기동 시 예외(fail-fast)")
    void 신뢰_목록_형식_오류(String entry) {
        assertThatThrownBy(() -> ClientIpResolver.configureTrustedProxies(List.of(entry)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("기본값(application.yaml) 대역은 모두 파싱된다")
    void 기본값_파싱() {
        ClientIpResolver.configureTrustedProxies(List.of(
                "127.0.0.0/8", "::1", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"));

        assertThat(ClientIpResolver.resolve(request("192.168.0.5", "127.0.0.1", "203.0.113.7")))
                .isEqualTo("203.0.113.7");
        // 172.32.x는 172.16.0.0/12 밖 - 비신뢰
        assertThat(ClientIpResolver.resolve(request("172.32.0.1", "127.0.0.1", "203.0.113.7")))
                .isEqualTo("172.32.0.1");
    }
}

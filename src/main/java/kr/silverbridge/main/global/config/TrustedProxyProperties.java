package kr.silverbridge.main.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 클라이언트 IP 해석에 쓰는 신뢰 프록시 목록 (2026-10-02, AUTH-G28·FEUX-G07·XCUT-G02).
 *
 * <p>여기 든 주소에서 온 요청만 {@code X-Forwarded-For}를 해석한다({@link kr.silverbridge.main.global.util.ClientIpResolver}).
 * FE 서버(BFF)의 실제 IP/CIDR는 코드에 넣지 말고 서버 {@code .env.dev}의 {@code CLIENT_IP_TRUSTED_PROXIES}로 주입한다.
 * 환경변수는 기본값을 <b>대체</b>하므로 루프백·사설망이 계속 필요하면 함께 적을 것.
 *
 * <p>⚠️ 공인 대역을 넓게 넣지 말 것 - 목록 안의 주소는 전달 헤더를 마음대로 쓸 수 있어 속도제한 키를 바꿀 수 있다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.client-ip")
public class TrustedProxyProperties {

    /** 신뢰 프록시 IP 또는 CIDR (IPv4·IPv6). 형식이 틀리면 기동 실패 */
    private List<String> trustedProxies = new ArrayList<>();
}

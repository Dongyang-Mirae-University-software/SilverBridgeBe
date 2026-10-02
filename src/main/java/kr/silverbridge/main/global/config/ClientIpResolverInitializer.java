package kr.silverbridge.main.global.config;

import jakarta.annotation.PostConstruct;
import kr.silverbridge.main.global.util.ClientIpResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 기동 시 신뢰 프록시 목록을 {@link ClientIpResolver}에 주입한다.
 * ClientIpResolver는 호출처가 많은 정적 유틸이라 시그니처를 바꾸지 않고 설정만 넣는다.
 * 형식이 틀린 항목은 여기서 예외가 나 기동이 멈춘다(fail-fast).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClientIpResolverInitializer {

    private final TrustedProxyProperties properties;

    @PostConstruct
    void init() {
        ClientIpResolver.configureTrustedProxies(properties.getTrustedProxies());
        log.info("[CLIENT-IP] 신뢰 프록시 {}건 적용", properties.getTrustedProxies().size());
    }
}

package kr.silverbridge.main.global.config;

import kr.silverbridge.main.global.util.ClientIpResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yaml의 {@code app.client-ip.trusted-proxies} 기본값·환경변수 재정의가 목록으로 바인딩되고,
 * 그 값이 ClientIpResolver에 그대로 들어가는지 확인한다(콜론이 든 IPv6 기본값이 placeholder에서 잘리지 않는지 포함).
 */
@DisplayName("TrustedProxyProperties 바인딩")
class TrustedProxyPropertiesTest {

    @AfterEach
    void reset() {
        ClientIpResolver.configureTrustedProxies(List.of());
    }

    private TrustedProxyProperties bind(Map<String, Object> env) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-env", env));
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment)
                .bind("app.client-ip", TrustedProxyProperties.class)
                .get();
    }

    @Test
    @DisplayName("기본값은 루프백 + 사설망 6개 대역")
    void defaults() throws Exception {
        TrustedProxyProperties properties = bind(Map.of());

        assertThat(properties.getTrustedProxies()).containsExactly(
                "127.0.0.0/8", "::1", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7");
        new ClientIpResolverInitializer(properties).init();
    }

    @Test
    @DisplayName("CLIENT_IP_TRUSTED_PROXIES로 재정의하면 기본값을 대체한다")
    void envOverride() throws Exception {
        TrustedProxyProperties properties = bind(Map.of("CLIENT_IP_TRUSTED_PROXIES", "198.51.100.10,127.0.0.1"));

        assertThat(properties.getTrustedProxies()).containsExactly("198.51.100.10", "127.0.0.1");
        new ClientIpResolverInitializer(properties).init();
    }
}

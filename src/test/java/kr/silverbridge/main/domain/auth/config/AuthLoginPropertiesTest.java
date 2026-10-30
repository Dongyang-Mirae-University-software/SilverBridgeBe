package kr.silverbridge.main.domain.auth.config;

import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 로그인 잠금 시간 기본값(2026-10-30, 30분 → 10분)과 설정 바인딩을 고정한다.
 * application.yaml 을 실제로 읽어 auth.login.* 로 바인딩한다(컨텍스트 없이).
 */
class AuthLoginPropertiesTest {

    private static final String ENV_KEY = "AUTH_LOGIN_LOCK_TTL_MINUTES";

    @Test
    @DisplayName("환경변수가 없으면 잠금 시간은 10분, 실패 허용은 5회다")
    void 기본값_10분() throws IOException {
        assumeTrue(System.getenv(ENV_KEY) == null, "실행 환경에 " + ENV_KEY + " 가 설정돼 있으면 건너뛴다");

        AuthLoginProperties props = bind(Map.of());

        assertThat(props.getLockTtlMinutes()).isEqualTo(10L);
        assertThat(props.getMaxAttempts()).isEqualTo(5);
        assertThat(new AuthLoginProperties().getLockTtlMinutes())
                .as("yaml 없이 쓰일 때의 필드 기본값도 같아야 한다")
                .isEqualTo(10L);
    }

    @Test
    @DisplayName("AUTH_LOGIN_LOCK_TTL_MINUTES 를 지정하면 그 값을 쓴다")
    void 설정값을_따른다() throws IOException {
        AuthLoginProperties props = bind(Map.of(ENV_KEY, "15"));

        assertThat(props.getLockTtlMinutes()).isEqualTo(15L);
    }

    @Test
    @DisplayName("LOGIN_LOCKED 문구의 분 수는 기본 잠금 시간과 같고, 비밀번호 확인 잠금은 30분 문구를 따로 쓴다")
    void 잠금_문구가_기본값과_맞다() {
        long defaultMinutes = new AuthLoginProperties().getLockTtlMinutes();

        assertThat(ErrorCode.LOGIN_LOCKED.getMessage()).contains(defaultMinutes + "분 후");
        assertThat(ErrorCode.PASSWORD_CHECK_LOCKED.getMessage()).contains("30분 후");
        assertThat(ErrorCode.PASSWORD_CHECK_LOCKED.getStatus()).isEqualTo(ErrorCode.LOGIN_LOCKED.getStatus());
    }

    private AuthLoginProperties bind(Map<String, Object> overrides) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test-overrides", overrides));
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"))
                .forEach(env.getPropertySources()::addLast);
        return Binder.get(env).bind("auth.login", AuthLoginProperties.class).get();
    }
}

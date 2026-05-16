package kr.silverbridge.main.domain.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 로그인 잠금 정책 설정 (외부화).
 * 코드에 박혀 있던 상수를 application.yaml(auth.login.*)로 분리해
 * 환경별로 정책을 조정할 수 있게 한다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "auth.login")
public class AuthLoginProperties {

    /** 최대 로그인 실패 허용 횟수 (초과 시 잠금) */
    private int maxAttempts = 5;

    /** 잠금 유지 시간 (분) */
    private long lockTtlMinutes = 30L;
}

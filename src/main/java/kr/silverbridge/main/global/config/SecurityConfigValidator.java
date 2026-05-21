package kr.silverbridge.main.global.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 애플리케이션 시작 시 핵심 보안 설정의 무결성을 검증한다.
 *
 * 검증 대상:
 * - JWT secret: 길이(256bit) 및 알려진 약한 값 거부
 *
 * 검증 실패 시 애플리케이션 시작을 중단한다. (프로덕션 배포 시 약한 기본값 사용 차단)
 */
@Slf4j
@Component
public class SecurityConfigValidator {

    // 과거 코드베이스에 존재했던 기본값들 — 유출된 것으로 간주하여 거부
    private static final Set<String> KNOWN_WEAK_JWT_SECRETS = Set.of(
            "main-backend-secret-key-must-be-at-least-256bit-long-for-hs256"
    );

    private static final int MIN_JWT_SECRET_LENGTH = 32;   // 256bit = 32 bytes (ASCII 기준)

    private final String jwtSecret;

    public SecurityConfigValidator(@Value("${jwt.secret}") String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    @PostConstruct
    public void validate() {
        validateJwtSecret();
        log.info("보안 설정 검증 통과: JWT secret 정상");
    }

    private void validateJwtSecret() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET 환경변수가 설정되지 않았습니다. .env.dev 또는 운영 환경변수로 지정하세요.");
        }
        if (jwtSecret.length() < MIN_JWT_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "JWT_SECRET 길이가 너무 짧습니다. 최소 " + MIN_JWT_SECRET_LENGTH + "자 이상 필요.");
        }
        if (KNOWN_WEAK_JWT_SECRETS.contains(jwtSecret)) {
            throw new IllegalStateException(
                    "JWT_SECRET에 공개된 약한 기본값이 사용되었습니다. 새 랜덤 값으로 교체하세요.");
        }
    }
}

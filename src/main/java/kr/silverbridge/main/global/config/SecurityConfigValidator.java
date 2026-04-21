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
 * - AI 서버 API 키: 최소 길이 및 알려진 placeholder 거부
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
    private static final Set<String> KNOWN_WEAK_AI_KEYS = Set.of(
            "change-me-in-production",
            "changeme",
            "test",
            "dev"
    );

    private static final int MIN_JWT_SECRET_LENGTH = 32;   // 256bit = 32 bytes (ASCII 기준)
    private static final int MIN_AI_KEY_LENGTH    = 32;

    private final String jwtSecret;
    private final String aiServerApiKey;

    public SecurityConfigValidator(
            @Value("${jwt.secret}") String jwtSecret,
            @Value("${ai.server.api-key}") String aiServerApiKey
    ) {
        this.jwtSecret = jwtSecret;
        this.aiServerApiKey = aiServerApiKey;
    }

    @PostConstruct
    public void validate() {
        validateJwtSecret();
        validateAiServerApiKey();
        log.info("보안 설정 검증 통과: JWT·AI 키 정상");
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

    private void validateAiServerApiKey() {
        if (aiServerApiKey == null || aiServerApiKey.isBlank()) {
            throw new IllegalStateException(
                    "AI_SERVER_API_KEY 환경변수가 설정되지 않았습니다. .env.dev 또는 운영 환경변수로 지정하세요.");
        }
        if (aiServerApiKey.length() < MIN_AI_KEY_LENGTH) {
            throw new IllegalStateException(
                    "AI_SERVER_API_KEY 길이가 너무 짧습니다. 최소 " + MIN_AI_KEY_LENGTH + "자 이상 필요.");
        }
        if (KNOWN_WEAK_AI_KEYS.contains(aiServerApiKey)) {
            throw new IllegalStateException(
                    "AI_SERVER_API_KEY에 공개된 약한 기본값이 사용되었습니다. 새 랜덤 값으로 교체하세요.");
        }
    }
}

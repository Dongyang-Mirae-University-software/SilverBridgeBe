package kr.silverbridge.main.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SecurityConfigValidator 순수 단위 테스트 (Spring 컨텍스트 미사용).
 * 시작 시점 보안 설정 검증 로직만 검증한다. 실제 시크릿 값은 사용하지 않는다.
 */
class SecurityConfigValidatorTest {

    // 길이·약한 값 조건을 모두 통과하는 임의의 JWT secret (실제 운영 값 아님)
    private static final String VALID_JWT_SECRET = "unit-test-jwt-secret-value-32bytes-or-more";

    private SecurityConfigValidator validatorWithKakaoSecret(String kakaoClientSecret) {
        return new SecurityConfigValidator(VALID_JWT_SECRET, kakaoClientSecret);
    }

    @Nested
    @DisplayName("Kakao client secret 검증")
    class KakaoClientSecret {

        @Test
        @DisplayName("32자 이상 정상 값이면 통과한다")
        void passesWhenValid() {
            // 32자 임의 값 — 실제 카카오 시크릿 아님
            String validSecret = "abcdefghijklmnopqrstuvwxyz123456";

            assertThatCode(() -> validatorWithKakaoSecret(validSecret).validate())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("blank이면 강도 검증을 건너뛴다 (존재 검증은 RequiredPropertiesValidator 담당)")
        void skipsWhenBlank() {
            assertThatCode(() -> validatorWithKakaoSecret("").validate())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("32자 미만이면 시작을 중단한다")
        void failsWhenTooShort() {
            assertThatThrownBy(() -> validatorWithKakaoSecret("short-secret").validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("KAKAO_CLIENT_SECRET");
        }

        @Test
        @DisplayName("알려진 placeholder/약한 값이면 시작을 중단한다")
        void failsWhenWeak() {
            // 길이 검증(>=32)은 통과하지만 placeholder 목록에 포함된 값
            assertThatThrownBy(() -> validatorWithKakaoSecret("your-kakao-client-secret-placeholder").validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("placeholder");
        }

        @Test
        @DisplayName("대소문자가 섞여도 약한 값으로 인식한다")
        void failsWhenWeakIgnoringCase() {
            assertThatThrownBy(() -> validatorWithKakaoSecret("YOUR-KAKAO-CLIENT-SECRET-PLACEHOLDER").validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("placeholder");
        }
    }
}

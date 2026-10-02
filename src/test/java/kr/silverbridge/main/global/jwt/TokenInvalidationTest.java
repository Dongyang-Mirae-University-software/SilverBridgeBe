package kr.silverbridge.main.global.jwt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenInvalidationTest {

    @Test
    @DisplayName("저장값은 ms를 초로 내림한 값이다")
    void encodeFloorsToSeconds() {
        assertThat(TokenInvalidation.encode(1_759_380_000_999L)).isEqualTo("1759380000");
    }

    @Test
    @DisplayName("초 값은 그대로, 배포 전 ms 값은 초로 환산해 읽는다")
    void parseHandlesSecondsAndLegacyMillis() {
        assertThat(TokenInvalidation.parseEpochSecond("1759380000")).isEqualTo(1_759_380_000L);
        assertThat(TokenInvalidation.parseEpochSecond("1759380000999")).isEqualTo(1_759_380_000L);
    }

    @Test
    @DisplayName("숫자가 아닌 값은 NumberFormatException - 호출자가 저장소 오류로 다룬다")
    void parseRejectsMalformed() {
        assertThatThrownBy(() -> TokenInvalidation.parseEpochSecond("abc"))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    @DisplayName("같은 초 발급은 허용, 앞선 초 발급만 무효")
    void sameSecondAllowedEarlierRevoked() {
        long invalidatedSec = 1_759_380_000L;
        assertThat(TokenInvalidation.isRevoked(1_759_380_000_000L, invalidatedSec)).isFalse();
        assertThat(TokenInvalidation.isRevoked(1_759_379_999_000L, invalidatedSec)).isTrue();
        assertThat(TokenInvalidation.isRevoked(1_759_380_001_000L, invalidatedSec)).isFalse();
    }
}

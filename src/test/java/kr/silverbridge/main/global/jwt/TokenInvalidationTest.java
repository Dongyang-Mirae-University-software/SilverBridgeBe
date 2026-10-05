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
    @DisplayName("숫자가 아닌 값은 CorruptValueException - 호출자가 손상 값으로 다룬다(원문은 메시지에 싣지 않는다)")
    void parseRejectsMalformed() {
        assertThatThrownBy(() -> TokenInvalidation.parseEpochSecond("abc"))
                .isInstanceOf(TokenInvalidation.CorruptValueException.class)
                .hasMessage("non-numeric");
        assertThatThrownBy(() -> TokenInvalidation.parseEpochSecond("99999999999999999999999"))
                .isInstanceOf(TokenInvalidation.CorruptValueException.class);
    }

    @Test
    @DisplayName("시각 검사 - 지금·과거·시계 차이 이내 미래는 정상, 음수·먼 미래는 손상 (XCUT-G03)")
    void parseWithNowRejectsImpossibleValues() {
        long nowMs = 1_759_380_000_000L;
        long nowSec = nowMs / 1000;
        assertThat(TokenInvalidation.parseEpochSecond(String.valueOf(nowSec), nowMs)).isEqualTo(nowSec);
        assertThat(TokenInvalidation.parseEpochSecond(String.valueOf(nowSec - 600), nowMs)).isEqualTo(nowSec - 600);
        long edge = nowSec + TokenInvalidation.FUTURE_SKEW_SECONDS;
        assertThat(TokenInvalidation.parseEpochSecond(String.valueOf(edge), nowMs)).isEqualTo(edge);
        // 옛 ms 형식도 같은 규칙
        assertThat(TokenInvalidation.parseEpochSecond(String.valueOf(nowMs), nowMs)).isEqualTo(nowSec);

        assertThatThrownBy(() -> TokenInvalidation.parseEpochSecond(String.valueOf(edge + 1), nowMs))
                .isInstanceOf(TokenInvalidation.CorruptValueException.class)
                .hasMessage("future");
        assertThatThrownBy(() -> TokenInvalidation.parseEpochSecond("-5", nowMs))
                .isInstanceOf(TokenInvalidation.CorruptValueException.class)
                .hasMessage("negative");
        assertThatThrownBy(() -> TokenInvalidation.parseEpochSecond("{broken", nowMs))
                .isInstanceOf(TokenInvalidation.CorruptValueException.class);
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

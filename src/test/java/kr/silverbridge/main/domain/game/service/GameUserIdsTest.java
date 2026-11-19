package kr.silverbridge.main.domain.game.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 게임 사용자 번호가 FE {@code toGameUserId}(src/utils/game/userId.ts)와 같은 값인지 - 다르면 보호자 화면에 기록이 0으로 보인다.
 * 기대값은 FE 함수를 node로 직접 돌려 얻었다(2026-11-19).
 */
class GameUserIdsTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "aB3x9Z, 1455350745",
            "pVFG3u, 1006924279",
            "WRD001, 1726428984",
            "zzzzzz, 685785664",
            "a, 97"
    })
    @DisplayName("FE toGameUserId 와 같은 값")
    void sameAsFrontend(String userId, long expected) {
        assertThat(GameUserIds.of(userId)).isEqualTo(expected);
    }

    @Test
    @DisplayName("해시가 -2^31 이면 2147483648 (JS Math.abs 와 같게, int 로는 음수로 남는다)")
    void minIntHash() {
        assertThat(GameUserIds.fromHash(Integer.MIN_VALUE)).isEqualTo(2_147_483_648L);
    }

    @Test
    @DisplayName("해시가 0 이면 1 (JS `|| 1`)")
    void zeroHash() {
        assertThat(GameUserIds.fromHash(0)).isEqualTo(1L);
    }
}

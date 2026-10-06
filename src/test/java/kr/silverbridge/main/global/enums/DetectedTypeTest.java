package kr.silverbridge.main.global.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DetectedTypeTest {

    @Test
    @DisplayName("화재·낙상·흉기만 이상감지 대상이고 normal·unknown은 항상 무시한다")
    void isDetectable() {
        assertThat(DetectedType.FIRE.isDetectable()).isTrue();
        assertThat(DetectedType.FALL.isDetectable()).isTrue();
        assertThat(DetectedType.WEAPON.isDetectable()).isTrue();
        assertThat(DetectedType.NORMAL.isDetectable()).isFalse();
        assertThat(DetectedType.UNKNOWN.isDetectable()).isFalse();
    }

    @Test
    @DisplayName("AI 문자열 매핑: fall → FALL, knife → WEAPON, smoke → FIRE")
    void fromAi() {
        assertThat(DetectedType.fromAi("fall")).isEqualTo(DetectedType.FALL);
        assertThat(DetectedType.fromAi("knife")).isEqualTo(DetectedType.WEAPON);
        assertThat(DetectedType.fromAi("smoke")).isEqualTo(DetectedType.FIRE);
    }
}

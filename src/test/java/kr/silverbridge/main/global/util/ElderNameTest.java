package kr.silverbridge.main.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ElderNameTest {

    @Test
    @DisplayName("이름이 있으면 '이름님'")
    void withName() {
        assertThat(ElderName.honorific("김영희")).isEqualTo("김영희님");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "어르신", " 어르신 "})
    @DisplayName("이름이 없거나 대체 이름이면 '어르신님'이 아니라 '어르신'")
    void fallback(String name) {
        assertThat(ElderName.honorific(name)).isEqualTo("어르신");
    }

    @Test
    @DisplayName("대체 이름은 '어르신'이다(화면 용어 통일, 2026-11-22)")
    void fallbackValue() {
        assertThat(ElderName.FALLBACK).isEqualTo("어르신");
    }
}

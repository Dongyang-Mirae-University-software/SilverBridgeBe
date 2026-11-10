package kr.silverbridge.main.global.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserIdFormatTest {

    @Test
    @DisplayName("영숫자 1~6자만 통과한다")
    void acceptsAlnumUpToSix() {
        assertThat(UserIdFormat.isWellFormed("a")).isTrue();
        assertThat(UserIdFormat.isWellFormed("aB3x9Z")).isTrue();
    }

    @Test
    @DisplayName("null·빈 값·7자 이상·특수문자·끝 줄바꿈은 거절한다")
    void rejectsOthers() {
        assertThat(UserIdFormat.isWellFormed(null)).isFalse();
        assertThat(UserIdFormat.isWellFormed("")).isFalse();
        assertThat(UserIdFormat.isWellFormed("abcdefg")).isFalse();
        assertThat(UserIdFormat.isWellFormed("ab-c")).isFalse();
        assertThat(UserIdFormat.isWellFormed("abc\n")).isFalse();
        assertThat(UserIdFormat.isWellFormed("한글")).isFalse();
    }
}

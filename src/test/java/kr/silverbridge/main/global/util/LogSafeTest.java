package kr.silverbridge.main.global.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 로그에 남기는 사용자 입력 식별자 정리 (2026-11-10 전체 점검 M-1·L-1·L-2). */
class LogSafeTest {

    @Test
    @DisplayName("영숫자와 _ - . / 는 그대로 둔다")
    void keepsSafeCharacters() {
        assertThat(LogSafe.id("ward_aB3x9ZkQ12345678")).isEqualTo("ward_aB3x9ZkQ12345678");
        assertThat(LogSafe.id("/topic/GD0001/sos-triggered")).isEqualTo("/topic/GD0001/sos-triggered");
    }

    @Test
    @DisplayName("줄바꿈·제어문자·공백·한글은 ?로 바꾼다 - 로그 줄 위조 방지")
    void replacesUnsafeCharacters() {
        String out = LogSafe.id("x\r\n2026 WARN [IDOR-ATTEMPT] 가짜\t줄");

        assertThat(out).doesNotContain("\n", "\r", "\t", " ", "[", "가");
        assertThat(out).startsWith("x??2026?WARN?");
    }

    @Test
    @DisplayName("64자를 넘으면 자르고 원래 길이를 붙인다")
    void truncatesLongValues() {
        String out = LogSafe.id("a".repeat(8000));

        assertThat(out).isEqualTo("a".repeat(64) + "...(len=8000)");
    }

    @Test
    @DisplayName("null은 글자 null로 남긴다")
    void nullValue() {
        assertThat(LogSafe.id(null)).isEqualTo("null");
    }
}

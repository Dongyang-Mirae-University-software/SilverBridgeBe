package kr.silverbridge.main.global.validation;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;

import static org.assertj.core.api.Assertions.assertThat;

class TextSanitizerTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    static class Single {
        @NoControlChars
        @VisibleText
        String text;

        Single(String text) {
            this.text = text;
        }
    }

    static class Multi {
        @NoControlChars(allowLineBreaks = true)
        String text;

        Multi(String text) {
            this.text = text;
        }
    }

    @Test
    @DisplayName("sanitize: null → null, 앞뒤 공백 제거")
    void nullAndTrim() {
        assertThat(TextSanitizer.sanitize(null)).isNull();
        assertThat(TextSanitizer.sanitize("  홍길동  ")).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("sanitize: NUL·제어문자 제거")
    void controlChars() {
        assertThat(TextSanitizer.sanitize("홍\u0000길\u0007동\u007F")).isEqualTo("홍길동");
        assertThat(TextSanitizer.sanitize("a\tb\nc")).isEqualTo("abc");
    }

    @Test
    @DisplayName("sanitize: 제로폭·BOM·word joiner 제거")
    void zeroWidth() {
        assertThat(TextSanitizer.sanitize("홍​길‌동‍﻿⁠")).isEqualTo("홍길동");
        assertThat(TextSanitizer.sanitize("‎‮abc")).isEqualTo("abc");
    }

    @Test
    @DisplayName("sanitize: NBSP·전각공백·얇은공백 → 일반 공백, 연속 공백은 하나로, 앞뒤 trim")
    void spaces() {
        assertThat(TextSanitizer.sanitize(" 홍　　길 동 ")).isEqualTo("홍 길 동");
        assertThat(TextSanitizer.sanitize(" 　​ ")).isEmpty();
    }

    @Test
    @DisplayName("sanitize: NFD(분해형) 한글 → NFC")
    void nfc() {
        String nfd = Normalizer.normalize("한글", Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo("한글");
        assertThat(TextSanitizer.sanitize(nfd)).isEqualTo("한글");
    }

    @Test
    @DisplayName("sanitizeMultiline: 줄바꿈 보존(\\r\\n→\\n), 줄별 공백 정리, 가장자리 줄바꿈 제거")
    void multiline() {
        assertThat(TextSanitizer.sanitizeMultiline("\n 첫 줄  \r\n\u0000둘째　줄\r\n\n"))
                .isEqualTo("첫 줄\n둘째 줄");
        assertThat(TextSanitizer.sanitizeMultiline(null)).isNull();
    }

    @Test
    @DisplayName("removeControlChars / removeFormatChars / containsControlChars")
    void helpers() {
        assertThat(TextSanitizer.removeControlChars("a\u0000b\nc")).isEqualTo("abc");
        assertThat(TextSanitizer.removeFormatChars("a​b﻿c")).isEqualTo("abc");
        assertThat(TextSanitizer.removeControlChars(null)).isNull();
        assertThat(TextSanitizer.containsControlChars("a\u0000", false)).isTrue();
        assertThat(TextSanitizer.containsControlChars("a\nb\t", false)).isTrue();
        assertThat(TextSanitizer.containsControlChars("a\nb\t\r", true)).isFalse();
        assertThat(TextSanitizer.containsControlChars("a\u0000", true)).isTrue();
        assertThat(TextSanitizer.containsControlChars(null, false)).isFalse();
    }

    @Test
    @DisplayName("visibleLength: 이모지(서로게이트 쌍)는 1글자, 제로폭 제외")
    void visibleLength() {
        String emoji = "😀"; // U+1F600, String.length()=2
        assertThat(emoji.length()).isEqualTo(2);
        assertThat(TextSanitizer.codePointLength(emoji)).isEqualTo(1);
        assertThat(TextSanitizer.visibleLength("가" + emoji + "나")).isEqualTo(3);
        assertThat(TextSanitizer.visibleLength("  가​나  ")).isEqualTo(2);
        assertThat(TextSanitizer.visibleLength("가　나")).isEqualTo(3); // 공백 1칸은 글자 수에 포함
        assertThat(TextSanitizer.visibleLength(null)).isZero();
        assertThat(TextSanitizer.codePointLength(null)).isZero();
    }

    @Test
    @DisplayName("hasVisibleChar: 공백류·제로폭·한글 채움·점자 빈칸·결합문자 단독은 보이지 않는다")
    void hasVisibleChar() {
        assertThat(TextSanitizer.hasVisibleChar(null)).isFalse();
        assertThat(TextSanitizer.hasVisibleChar("")).isFalse();
        assertThat(TextSanitizer.hasVisibleChar("   ")).isFalse();
        assertThat(TextSanitizer.hasVisibleChar(" 　 ")).isFalse();
        assertThat(TextSanitizer.hasVisibleChar("​‌‍﻿⁠")).isFalse();
        assertThat(TextSanitizer.hasVisibleChar("\u0000\t\n")).isFalse();
        assertThat(TextSanitizer.hasVisibleChar("ㅤᅟᅠﾠ")).isFalse();
        assertThat(TextSanitizer.hasVisibleChar("⠀")).isFalse();
        assertThat(TextSanitizer.hasVisibleChar("́")).isFalse(); // 결합 악센트만
        assertThat(TextSanitizer.hasVisibleChar("​가​")).isTrue();
        assertThat(TextSanitizer.hasVisibleChar("😀")).isTrue();
        assertThat(TextSanitizer.hasVisibleChar(".")).isTrue();
        assertThat(TextSanitizer.hasVisibleChar("1")).isTrue();
    }

    @Test
    @DisplayName("@NoControlChars + @VisibleText: 정상·NUL·제로폭 전용·null")
    void annotations() {
        assertThat(validator.validate(new Single("홍길동"))).isEmpty();
        assertThat(validator.validate(new Single(null))).isEmpty();
        assertThat(validator.validate(new Single("홍\u0000길"))).hasSize(1);
        assertThat(validator.validate(new Single("​​"))).hasSize(1);
        assertThat(validator.validate(new Single(" 　"))).hasSize(1);
        assertThat(validator.validate(new Single("a\nb"))).hasSize(1);
    }

    @Test
    @DisplayName("@NoControlChars(allowLineBreaks=true): 줄바꿈·탭은 허용, NUL은 거부")
    void multilineAnnotation() {
        assertThat(validator.validate(new Multi("a\nb\r\n\tc"))).isEmpty();
        assertThat(validator.validate(new Multi("a\u0000b"))).hasSize(1);
        assertThat(validator.validate(new Multi("a\u001Bb"))).hasSize(1);
    }
}

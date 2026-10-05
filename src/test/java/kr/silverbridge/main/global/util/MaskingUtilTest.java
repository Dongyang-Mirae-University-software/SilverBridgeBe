package kr.silverbridge.main.global.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingUtilTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "홍길동, 홍*동",
            "남궁민수, 남**수",
            "홍길, 홍*",
            "홍, *",
            "'  홍길동 ', 홍*동",
            "John Smith, J********h"
    })
    @DisplayName("이름은 첫 글자와 (3자 이상이면) 마지막 글자만 남긴다 - 연결 요청 전 확인(CONN-G06)")
    void 이름_마스킹(String name, String expected) {
        assertThat(MaskingUtil.maskName(name)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("이름이 없으면 고정값 - 길이도 드러내지 않는다")
    void 빈_이름(String name) {
        assertThat(MaskingUtil.maskName(name)).isEqualTo("***");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("보조 문자(이모지)를 반쪽으로 자르지 않는다")
    void 코드포인트_단위() {
        assertThat(MaskingUtil.maskName("😀길😀")).isEqualTo("😀*😀");
    }
}

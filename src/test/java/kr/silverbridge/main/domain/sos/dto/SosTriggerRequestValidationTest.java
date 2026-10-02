package kr.silverbridge.main.domain.sos.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SOS 요청 DTO 위치 정규화 (SOS-G15)")
class SosTriggerRequestValidationTest {

    private static final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("앞뒤 공백 포함 102자 → 정리 후 100자면 통과")
    void 정리후_100자_통과() {
        var r = new SosTriggerRequest(" " + "가".repeat(100) + " ", null);
        assertThat(validator.validate(r)).isEmpty();
        assertThat(r.location()).hasSize(100);
    }

    @Test
    @DisplayName("정리 후에도 101자면 400")
    void 정리후_101자_거부() {
        assertThat(validator.validate(new SosTriggerRequest("가".repeat(101), null))).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\u200b", "\u00a0\u00a0", "\u3000", "\u0000"})
    @DisplayName("보이지 않는 글자만이면 null(400 아님)")
    void 빈값_null(String v) {
        var r = new SosTriggerRequest(v, null);
        assertThat(r.location()).isNull();
        assertThat(validator.validate(r)).isEmpty();
    }

    @Test
    @DisplayName("이모지 위치는 허용(@Size는 UTF-16 기준이라 이모지 50개까지)")
    void 이모지() {
        assertThat(validator.validate(new SosTriggerRequest("😀".repeat(50), null))).isEmpty();
    }
}

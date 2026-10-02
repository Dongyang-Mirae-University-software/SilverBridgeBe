package kr.silverbridge.main.domain.inquiry.dto;

import kr.silverbridge.main.global.enums.InquiryCategory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("문의 작성 DTO 검증 (XCUT-G23/G26)")
class InquiryCreateRequestValidationTest {

    private static final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private InquiryCreateRequest req(String title, String content) {
        InquiryCreateRequest r = new InquiryCreateRequest();
        ReflectionTestUtils.setField(r, "category", InquiryCategory.ETC);
        ReflectionTestUtils.setField(r, "title", title);
        ReflectionTestUtils.setField(r, "content", content);
        return r;
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u200b\u200b", "\u00a0\u00a0", "\u3000\u3000", "\u0000"})
    @DisplayName("보이지 않는 글자·NUL만인 제목은 400")
    void 보이지않는_제목_거부(String title) {
        assertThat(validator.validate(req(title, "내용"))).isNotEmpty();
    }

    @Test
    @DisplayName("제목은 정리본을 돌려주고 이모지는 허용")
    void 제목_정리() {
        InquiryCreateRequest r = req("  알림 😀\u200b ", "내용");
        assertThat(validator.validate(r)).isEmpty();
        assertThat(r.getTitle()).isEqualTo("알림 😀");
    }

    @Test
    @DisplayName("내용은 줄바꿈 허용, NUL은 400")
    void 내용_제어문자() {
        assertThat(validator.validate(req("제목", "첫줄\n둘째줄"))).isEmpty();
        assertThat(validator.validate(req("제목", "내용\u0000"))).isNotEmpty();
    }

    @Test
    @DisplayName("제목 101자는 400")
    void 제목_길이() {
        assertThat(validator.validate(req("가".repeat(101), "내용"))).isNotEmpty();
    }
}

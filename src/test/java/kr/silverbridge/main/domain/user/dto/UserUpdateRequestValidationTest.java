package kr.silverbridge.main.domain.user.dto;

import kr.silverbridge.main.global.enums.Gender;
import java.time.LocalDate;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("내 정보 수정 DTO 검증 (USER-G14, XCUT-G23/G26)")
class UserUpdateRequestValidationTest {

    private static final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private UserUpdateRequest req(String name, String address) {
        UserUpdateRequest r = new UserUpdateRequest();
        ReflectionTestUtils.setField(r, "name", name);
        ReflectionTestUtils.setField(r, "phone", "01012345678");
        ReflectionTestUtils.setField(r, "gender", Gender.MALE);
        ReflectionTestUtils.setField(r, "birthDate", LocalDate.of(1950, 1, 1));
        ReflectionTestUtils.setField(r, "postcode", "06236");
        ReflectionTestUtils.setField(r, "address", address);
        ReflectionTestUtils.setField(r, "addressDetail", "101동");
        return r;
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u200b\u200b", "\u00a0\u00a0", "\u3000\u3000", "\u0000"})
    @DisplayName("보이지 않는 글자·NUL만인 이름은 400")
    void 보이지않는_이름_거부(String name) {
        assertThat(validator.validate(req(name, "서울"))).isNotEmpty();
    }

    @Test
    @DisplayName("이름 앞뒤 공백·서식문자는 정리해 돌려준다")
    void 이름_정리() {
        UserUpdateRequest r = req("  홍\u200b길동\u00a0 ", "서울");
        assertThat(validator.validate(r)).isEmpty();
        assertThat(r.getName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("이모지 포함 이름 허용, 21자는 거부")
    void 이모지_길이() {
        assertThat(validator.validate(req("홍길동😀", "서울"))).isEmpty();
        assertThat(validator.validate(req("가".repeat(21), "서울"))).isNotEmpty();
    }

    @Test
    @DisplayName("주소에 NUL·제어문자가 있으면 400")
    void 주소_NUL_거부() {
        assertThat(validator.validate(req("홍길동", "서울\u0000시"))).isNotEmpty();
        assertThat(validator.validate(req("홍길동", "서울\n시"))).isNotEmpty();
    }
}

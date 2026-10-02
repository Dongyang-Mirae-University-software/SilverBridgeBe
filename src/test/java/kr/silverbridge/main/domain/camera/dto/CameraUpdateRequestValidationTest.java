package kr.silverbridge.main.domain.camera.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CameraUpdateRequest} 입력 검증 (ANOM-G12).
 * 방 이름은 생략(null)하면 미변경이지만, 보냈다면 등록과 같이 비어 있으면 안 된다 - 화재 알림 문구의 위치로 쓰인다.
 */
class CameraUpdateRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", " ", "　", "​​"})
    @DisplayName("빈 값·공백·제로폭 문자만 있는 방 이름 → '설치 위치(방 이름)를 입력해주세요.'")
    void blankLabel_rejected(String label) {
        Set<ConstraintViolation<CameraUpdateRequest>> violations =
                validator.validate(new CameraUpdateRequest(label, null));

        assertThat(violations).extracting(ConstraintViolation::getMessage)
                .contains("설치 위치(방 이름)를 입력해주세요.");
    }

    @Test
    @DisplayName("방 이름 생략(null) → 위반 없음 (부분 수정: 미변경)")
    void nullLabel_allowed() {
        assertThat(validator.validate(new CameraUpdateRequest(null, false))).isEmpty();
    }

    @Test
    @DisplayName("제어문자(NUL) 포함 → 위반")
    void controlChars_rejected() {
        assertThat(validator.validate(new CameraUpdateRequest("안\u0000방", null))).isNotEmpty();
    }

    @Test
    @DisplayName("정상 이름 → 위반 없음, 31자는 길이 제한 위반")
    void normalAndLength() {
        assertThat(validator.validate(new CameraUpdateRequest("안방", null))).isEmpty();
        assertThat(validator.validate(new CameraUpdateRequest("가".repeat(31), null)))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("설치 위치는 최대 30자입니다.");
    }
}

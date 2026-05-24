package kr.silverbridge.main.global.validation;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 공유 {@link ValidPassword} 제약(문자 정책)의 단위 검증.
 * <p>
 * 길이(@Size)·필수(@NotBlank)는 별도 제약이 담당하므로 여기서는 문자 정책(영문·숫자·특수문자 포함,
 * 공백·한글·이모지 불가)만 검증한다. null은 통과(필수 여부는 @NotBlank 책임).
 */
class PasswordFieldValidationTest {

    private static final String MESSAGE = "비밀번호는 영문·숫자·특수문자를 포함하고, 공백 없이 8글자 이상이어야 합니다.";

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

    @Test
    @DisplayName("영문·숫자·특수문자 포함 → 위반 없음")
    void validPasswordPasses() {
        assertEquals(0, validator.validate(new PasswordFixture("Abcdef1!")).size());
    }

    @Test
    @DisplayName("null → 위반 없음 (필수 여부는 @NotBlank 책임)")
    void nullPasses() {
        assertEquals(0, validator.validate(new PasswordFixture(null)).size());
    }

    @Test
    @DisplayName("한글 포함 → 문자 정책 위반")
    void koreanFails() {
        var violations = validator.validate(new PasswordFixture("Abcd1234!한"));

        assertEquals(1, violations.size());
        assertEquals(MESSAGE, violations.iterator().next().getMessage());
    }

    @Test
    @DisplayName("이모지 포함 → 문자 정책 위반")
    void emojiFails() {
        var violations = validator.validate(new PasswordFixture("Abcd1234!🙂"));

        assertEquals(1, violations.size());
        assertEquals(MESSAGE, violations.iterator().next().getMessage());
    }

    @Test
    @DisplayName("공백 포함 → 문자 정책 위반")
    void whitespaceFails() {
        var violations = validator.validate(new PasswordFixture("Abc 123!x"));

        assertEquals(1, violations.size());
        assertEquals(MESSAGE, violations.iterator().next().getMessage());
    }

    @Test
    @DisplayName("특수문자 누락 → 문자 정책 위반")
    void missingSpecialCharacterFails() {
        var violations = validator.validate(new PasswordFixture("Abcdef12"));

        assertEquals(1, violations.size());
        assertEquals(MESSAGE, violations.iterator().next().getMessage());
    }

    private record PasswordFixture(@ValidPassword String password) {
    }
}

package kr.silverbridge.main.global.validation;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PasswordFieldValidationTest {

    private static jakarta.validation.ValidatorFactory validatorFactory;
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
    void passwordWithin24CharactersPasses() {
        PasswordFixture fixture = new PasswordFixture("Abcdefghijklmnopqrstuv1!");

        assertEquals(0, validator.validate(fixture).size());
    }

    @Test
    void passwordOver24CharactersFailsWithLengthMessage() {
        PasswordFixture fixture = new PasswordFixture("Abcdefghijklmnopqrstuvw1!");

        var violations = validator.validate(fixture);

        assertEquals(1, violations.size());
        assertEquals("비밀번호는 8자 이상 24자 이하여야 합니다.", violations.iterator().next().getMessage());
    }

    @Test
    void passwordWithKoreanPasses() {
        PasswordFixture fixture = new PasswordFixture("Abcd1234!한");

        assertEquals(0, validator.validate(fixture).size());
    }

    @Test
    void passwordWithEmojiFailsCharacterPolicy() {
        PasswordFixture fixture = new PasswordFixture("Abcd1234!🙂");

        var violations = validator.validate(fixture);

        assertEquals(1, violations.size());
        assertEquals(
                "비밀번호는 영문 또는 한글, 숫자, 특수문자만 사용할 수 있으며 각 종류를 1개 이상 포함해야 하고 공백을 사용할 수 없습니다.",
                violations.iterator().next().getMessage()
        );
    }

    private record PasswordFixture(
            @Size(min = 8, max = 24, message = "비밀번호는 8자 이상 24자 이하여야 합니다.")
            @Pattern(
                    regexp = "^(?=.*[A-Za-z가-힣])(?=.*[0-9])(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~])[A-Za-z0-9가-힣!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~]+$",
                    message = "비밀번호는 영문 또는 한글, 숫자, 특수문자만 사용할 수 있으며 각 종류를 1개 이상 포함해야 하고 공백을 사용할 수 없습니다."
            )
            @Utf8ByteLength(
                    max = 72,
                    message = "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다."
            )
            String password
    ) {
    }
}

package kr.silverbridge.main.global.validation;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Utf8ByteLengthValidatorTest {

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
    void asciiPasswordWithin72BytesPasses() {
        PasswordFixture fixture = new PasswordFixture("a".repeat(72));

        assertEquals(0, validator.validate(fixture).size());
    }

    @Test
    void asciiPasswordOver72BytesFails() {
        PasswordFixture fixture = new PasswordFixture("a".repeat(73));

        assertEquals(1, validator.validate(fixture).size());
    }

    @Test
    void multibytePasswordOver72BytesFails() {
        PasswordFixture fixture = new PasswordFixture("가".repeat(25));

        assertEquals(1, validator.validate(fixture).size());
    }

    private record PasswordFixture(
            @Utf8ByteLength(max = 72)
            String password
    ) {
    }
}

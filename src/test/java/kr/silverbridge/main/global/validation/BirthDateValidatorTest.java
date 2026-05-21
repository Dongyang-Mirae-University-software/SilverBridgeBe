package kr.silverbridge.main.global.validation;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ValidBirthDate} / {@link BirthDateValidator} 경계 검증.
 * 실제 Jakarta Validation 엔진으로 애너테이션 기본값(minAge=14, maxAge=120) 동작을 확인한다.
 */
class BirthDateValidatorTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    /** @ValidBirthDate 가 붙은 필드를 가진 검증 대상 홀더 */
    private static class Holder {
        @ValidBirthDate
        private final LocalDate birthDate;

        Holder(LocalDate birthDate) {
            this.birthDate = birthDate;
        }
    }

    private boolean isValid(LocalDate birthDate) {
        return validator.validate(new Holder(birthDate)).isEmpty();
    }

    @Test
    @DisplayName("미래 날짜는 거부된다")
    void futureDateRejected() {
        assertThat(isValid(LocalDate.now().plusDays(1))).isFalse();
    }

    @Test
    @DisplayName("오늘 날짜는 거부된다 (만 0세)")
    void todayRejected() {
        assertThat(isValid(LocalDate.now())).isFalse();
    }

    @Test
    @DisplayName("만 14세 미만은 거부된다")
    void underMinAgeRejected() {
        assertThat(isValid(LocalDate.now().minusYears(14).plusDays(1))).isFalse();
    }

    @Test
    @DisplayName("만 14세 정각은 허용된다")
    void exactlyMinAgeAccepted() {
        assertThat(isValid(LocalDate.now().minusYears(14))).isTrue();
    }

    @Test
    @DisplayName("일반 성인 생년월일은 허용된다")
    void normalAdultAccepted() {
        assertThat(isValid(LocalDate.of(1990, 3, 15))).isTrue();
    }

    @Test
    @DisplayName("만 120세 초과(비현실적으로 과거)는 거부된다 (A-L6)")
    void overMaxAgeRejected() {
        assertThat(isValid(LocalDate.now().minusYears(121))).isFalse();
    }

    @Test
    @DisplayName("null은 통과한다 (필수 여부는 @NotNull이 담당)")
    void nullPasses() {
        assertThat(isValid(null)).isTrue();
    }
}

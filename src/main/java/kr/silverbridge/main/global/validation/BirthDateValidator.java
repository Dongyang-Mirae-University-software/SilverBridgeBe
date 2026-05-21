package kr.silverbridge.main.global.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.LocalDate;
import java.time.Period;

public class BirthDateValidator implements ConstraintValidator<ValidBirthDate, LocalDate> {

    private int minAge;
    private int maxAge;

    @Override
    public void initialize(ValidBirthDate constraint) {
        this.minAge = constraint.minAge();
        this.maxAge = constraint.maxAge();
    }

    @Override
    public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
        // 필수 여부는 @NotNull이 담당 — 여기서는 null을 통과시켜 메시지를 분리한다.
        if (value == null) {
            return true;
        }
        LocalDate today = LocalDate.now();
        // 오늘·미래 날짜 차단
        if (!value.isBefore(today)) {
            return false;
        }
        // 만 나이 [minAge, maxAge] 범위 — 상한으로 비현실적으로 과거인 날짜 차단 (A-L6)
        int age = Period.between(value, today).getYears();
        return age >= minAge && age <= maxAge;
    }
}

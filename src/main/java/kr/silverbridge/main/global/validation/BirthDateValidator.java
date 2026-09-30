package kr.silverbridge.main.global.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;

public class BirthDateValidator implements ConstraintValidator<ValidBirthDate, LocalDate> {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

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
        // 서버 기본 시간대가 아니라 KST로 "오늘"을 정한다 - UTC 환경이면 자정~09시 사이 판정이 하루 어긋난다
        LocalDate today = LocalDate.now(KST);
        // 오늘·미래 날짜 차단
        if (!value.isBefore(today)) {
            return false;
        }
        // 만 나이 [minAge, maxAge] 범위 — 상한으로 비현실적으로 과거인 날짜 차단 (A-L6)
        int age = Period.between(value, today).getYears();
        return age >= minAge && age <= maxAge;
    }
}

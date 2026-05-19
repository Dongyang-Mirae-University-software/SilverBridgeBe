package kr.silverbridge.main.global.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * 생년월일 검증.
 * - 미래 날짜(오늘 포함) 입력 불가
 * - 만 {@link #minAge()}세 이상만 허용 (기본 14세)
 * <p>
 * null은 통과시킨다(필수 여부는 {@code @NotNull}로 별도 표현 — 메시지를 분리해 프론트가 구분하기 쉽게).
 */
@Documented
@Constraint(validatedBy = BirthDateValidator.class)
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
public @interface ValidBirthDate {

    String message() default "생년월일이 올바르지 않습니다. 미래 날짜는 입력할 수 없으며 만 14세 이상만 가입할 수 있습니다.";

    /** 최소 가입 연령(만 나이) */
    int minAge() default 14;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

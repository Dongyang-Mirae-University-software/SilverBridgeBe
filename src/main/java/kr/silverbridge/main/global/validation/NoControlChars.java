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
 * 제어문자(NUL 포함)가 없어야 한다. NUL은 PostgreSQL이 거절해 500이 되므로 입력 단계에서 400으로 막는다.
 * null은 통과(필수 여부는 {@code @NotBlank}). 여러 줄 입력은 {@code allowLineBreaks=true}.
 */
@Documented
@Constraint(validatedBy = NoControlCharsValidator.class)
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
public @interface NoControlChars {

    String message() default "허용되지 않는 문자가 포함되어 있습니다.";

    /** true면 \t \n \r 은 허용(본문처럼 여러 줄 입력용) */
    boolean allowLineBreaks() default false;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

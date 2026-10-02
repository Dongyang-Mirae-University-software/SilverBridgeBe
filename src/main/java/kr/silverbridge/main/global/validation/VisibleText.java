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
 * 눈에 보이는 글자가 1자 이상 있어야 한다. 제로폭 문자·NBSP·전각공백·한글 채움문자만으로 된 입력은
 * {@code @NotBlank}를 통과하지만 화면에는 빈 값이라 막는다. null은 통과(필수 여부는 {@code @NotBlank}).
 */
@Documented
@Constraint(validatedBy = VisibleTextValidator.class)
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
public @interface VisibleText {

    String message() default "내용을 입력해주세요.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

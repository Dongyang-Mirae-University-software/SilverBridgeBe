package kr.silverbridge.main.domain.admin.support;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * 코드포인트 기준 최대 길이(ADMIN-G22). {@code @Size}는 UTF-16 단위라 이모지 1개를 2글자로 세어,
 * 화면에서는 한도 안인 입력이 400으로 거절된다. 서비스가 저장하는 정리된 값({@code TextSanitizer})과
 * 같은 기준으로 센다. null은 통과(필수 여부는 {@code @NotBlank}).
 */
@Documented
@Constraint(validatedBy = CodePointSizeValidator.class)
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
public @interface CodePointSize {

    String message() default "허용된 길이를 초과했습니다.";

    /** 정리 후 코드포인트 최대 길이 */
    int max();

    /** true면 줄바꿈을 보존하는 여러 줄 정리 기준으로 센다(본문용) */
    boolean multiline() default false;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

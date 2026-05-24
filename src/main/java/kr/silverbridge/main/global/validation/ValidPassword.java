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
 * 비밀번호 문자 정책 검증 — 영문·숫자·특수문자를 모두 포함하고 공백·기타 문자(한글·이모지 등)는 불가.
 * <p>
 * 회원가입·비밀번호 변경·비밀번호 재설정에서 동일한 정규식이 중복되던 것을 단일 제약으로 통합한다 (B-USER-1).
 * 길이(8~64자)는 {@code @Size}, 필수 여부는 {@code @NotBlank}가 각각 담당한다 — 메시지를 분리해 프론트가 구분하기 쉽게.
 * 이에 맞춰 null은 통과시킨다(필수 여부는 {@code @NotBlank}가 표현).
 */
@Documented
@Constraint(validatedBy = PasswordValidator.class)
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
public @interface ValidPassword {

    String message() default "비밀번호는 영문·숫자·특수문자를 포함하고, 공백 없이 8글자 이상이어야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

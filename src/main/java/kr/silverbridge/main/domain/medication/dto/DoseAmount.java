package kr.silverbridge.main.domain.medication.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import kr.silverbridge.main.domain.medication.entity.DoseAmounts;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;

/**
 * 복용량(정) 검증 - 0.5 단위, 0.5 이상 99 이하(2026-10-22). {@code null}은 통과한다(생략 = 기본값 또는 미변경).
 * 등록·수정·일괄 등록이 같은 규칙을 쓴다({@link DoseAmounts}).
 */
@Documented
@Constraint(validatedBy = DoseAmount.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface DoseAmount {

    String message() default "복용량은 0.5 단위로 0.5~99 사이여야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<DoseAmount, BigDecimal> {
        @Override
        public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
            return value == null || DoseAmounts.isValid(value);
        }
    }
}

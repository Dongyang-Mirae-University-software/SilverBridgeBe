package kr.silverbridge.main.global.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class VisibleTextValidator implements ConstraintValidator<VisibleText, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // 필수 여부는 @NotBlank가 담당 — null은 통과시켜 메시지를 분리한다.
        return value == null || TextSanitizer.hasVisibleChar(value);
    }
}

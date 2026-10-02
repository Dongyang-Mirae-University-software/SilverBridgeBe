package kr.silverbridge.main.domain.admin.support;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import kr.silverbridge.main.global.validation.TextSanitizer;

public class CodePointSizeValidator implements ConstraintValidator<CodePointSize, String> {

    private int max;
    private boolean multiline;

    @Override
    public void initialize(CodePointSize annotation) {
        this.max = annotation.max();
        this.multiline = annotation.multiline();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        String cleaned = multiline ? TextSanitizer.sanitizeMultiline(value) : TextSanitizer.sanitize(value);
        return TextSanitizer.codePointLength(cleaned) <= max;
    }
}

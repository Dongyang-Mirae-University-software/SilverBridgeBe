package kr.silverbridge.main.global.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

public class PasswordValidator implements ConstraintValidator<ValidPassword, String> {

    // 영문·숫자·특수문자 각 1개 이상 + 허용 문자(영문/숫자/특수문자)만 — 공백·한글·이모지 등 불가
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "^(?=.*[A-Za-z])(?=.*[0-9])(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~])[A-Za-z0-9!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~]+$"
    );

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // 필수 여부는 @NotBlank, 길이는 @Size가 담당 — 여기서는 null을 통과시켜 메시지를 분리한다.
        if (value == null) {
            return true;
        }
        return PASSWORD_PATTERN.matcher(value).matches();
    }
}

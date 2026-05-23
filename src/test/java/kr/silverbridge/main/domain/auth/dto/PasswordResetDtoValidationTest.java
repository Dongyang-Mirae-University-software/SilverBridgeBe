package kr.silverbridge.main.domain.auth.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 비밀번호 재설정 발송 요청 DTO의 Bean Validation 제약 검증.
 *
 * <p>2026-05-23 정책 변경(always-200 → 404/400/429)으로 형식 오류 입력이 400으로 분기되는 것이
 * 중요해졌다(SPOT-M1). 형식 오류 → 제약 위반(=컨트롤러에서 400)을 단위로 고정한다.
 * HTTP 400 매핑 자체는 GlobalExceptionHandler가 MethodArgumentNotValidException을 일반 처리한다.
 *
 * <p>DTO는 {@code @Getter}만 두고 setter가 없으므로 {@link ReflectionTestUtils}로 필드를 주입한다.
 */
@DisplayName("비밀번호 재설정 발송 DTO 검증")
class PasswordResetDtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    private static final String NAME = "홍길동";
    private static final String VALID_PHONE = "01012345678";
    private static final String VALID_EMAIL = "user@example.com";

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private PasswordResetRequest emailRequest(String email) {
        PasswordResetRequest request = new PasswordResetRequest();
        ReflectionTestUtils.setField(request, "email", email);
        return request;
    }

    private PasswordResetSmsSendRequest smsRequest(String name, String phone) {
        PasswordResetSmsSendRequest request = new PasswordResetSmsSendRequest();
        ReflectionTestUtils.setField(request, "name", name);
        ReflectionTestUtils.setField(request, "phone", phone);
        return request;
    }

    private static Set<String> violatedFields(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }

    @Nested
    @DisplayName("이메일 방식 (PasswordResetRequest)")
    class EmailRequest {

        @Test
        @DisplayName("정상 이메일 → 위반 없음")
        void 정상_이메일_위반없음() {
            var violations = validator.validate(emailRequest(VALID_EMAIL));

            assertThat(violations).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"not-an-email", "plainaddress", "user example.com", "user@ example.com"})
        @DisplayName("형식 오류 이메일 → email 제약 위반 (→ 400)")
        void 형식오류_이메일_email위반(String invalidEmail) {
            var violations = validator.validate(emailRequest(invalidEmail));

            assertThat(violatedFields(violations)).contains("email");
        }

        @Test
        @DisplayName("빈 이메일 → email 제약 위반 (@NotBlank)")
        void 빈_이메일_email위반() {
            var violations = validator.validate(emailRequest("  "));

            assertThat(violatedFields(violations)).contains("email");
        }

        @Test
        @DisplayName("50자 초과 이메일 → email 제약 위반 (@Size)")
        void 길이초과_이메일_email위반() {
            // 형식은 유효하나 51자 — @Size(max=50)만 단독으로 걸리는지 확인
            String longLocalPart = "a".repeat(44); // 44 + "@bbbb.com"(9) = 53자
            var violations = validator.validate(emailRequest(longLocalPart + "@bbbb.com"));

            assertThat(violatedFields(violations)).contains("email");
        }
    }

    @Nested
    @DisplayName("SMS 방식 (PasswordResetSmsSendRequest)")
    class SmsRequest {

        @Test
        @DisplayName("정상 이름+전화번호 → 위반 없음")
        void 정상_입력_위반없음() {
            var violations = validator.validate(smsRequest(NAME, VALID_PHONE));

            assertThat(violations).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"010-1234-5678", "0101234", "010123456789", "abcdefghij", "+8201012345678"})
        @DisplayName("형식 오류 전화번호 → phone 제약 위반 (→ 400)")
        void 형식오류_전화번호_phone위반(String invalidPhone) {
            var violations = validator.validate(smsRequest(NAME, invalidPhone));

            assertThat(violatedFields(violations)).contains("phone");
        }

        @Test
        @DisplayName("빈 전화번호 → phone 제약 위반 (@NotBlank)")
        void 빈_전화번호_phone위반() {
            var violations = validator.validate(smsRequest(NAME, ""));

            assertThat(violatedFields(violations)).contains("phone");
        }

        @Test
        @DisplayName("빈 이름 → name 제약 위반 (@NotBlank)")
        void 빈_이름_name위반() {
            var violations = validator.validate(smsRequest("  ", VALID_PHONE));

            assertThat(violatedFields(violations)).contains("name");
        }
    }
}

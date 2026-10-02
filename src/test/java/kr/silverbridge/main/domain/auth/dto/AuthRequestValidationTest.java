package kr.silverbridge.main.domain.auth.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 인증 요청 DTO 검증 - 서비스(인증값 소비)에 닿기 전에 400으로 막혀야 하는 입력들. */
class AuthRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    @DisplayName("일반 가입 - 카카오 대체 이메일 형식은 '사용할 수 없는 이메일입니다.' (AUTH-G08)")
    void register_카카오대체이메일() {
        RegisterRequest req = new RegisterRequest();
        ReflectionTestUtils.setField(req, "email", "Kakao_123456@kakao.com");

        assertThat(messagesOf(req, "email")).contains("사용할 수 없는 이메일입니다.");

        ReflectionTestUtils.setField(req, "email", "kakao_fan@kakao.com");
        assertThat(messagesOf(req, "email")).doesNotContain("사용할 수 없는 이메일입니다.");
        ReflectionTestUtils.setField(req, "email", "user+e2e@gmail.com");
        assertThat(messagesOf(req, "email")).isEmpty();
    }

    @Test
    @DisplayName("이름 - 보이지 않는 문자만이면 '이름을 입력해주세요.' (AUTH-G13)")
    void 이름_보이지않는문자() {
        RegisterRequest register = new RegisterRequest();
        ReflectionTestUtils.setField(register, "name", "​　");
        FindEmailRequest find = new FindEmailRequest();
        ReflectionTestUtils.setField(find, "name", "​");
        PasswordResetSmsSendRequest sms = new PasswordResetSmsSendRequest();
        ReflectionTestUtils.setField(sms, "name", "ㅤ");
        KakaoRegisterRequest kakao = new KakaoRegisterRequest();
        ReflectionTestUtils.setField(kakao, "name", "​");

        assertThat(messagesOf(register, "name")).contains("이름을 입력해주세요.");
        assertThat(messagesOf(find, "name")).contains("이름을 입력해주세요.");
        assertThat(messagesOf(sms, "name")).contains("이름을 입력해주세요.");
        assertThat(messagesOf(kakao, "name")).contains("이름을 입력해주세요.");
    }

    @Test
    @DisplayName("카카오 가입 - 프로필 이미지는 카카오 CDN 주소·500자 이하만, pendingToken 필수 (AUTH-G22·AUTH-G07)")
    void kakaoRegister_이미지주소_pendingToken() {
        KakaoRegisterRequest req = new KakaoRegisterRequest();

        ReflectionTestUtils.setField(req, "profileImageUrl", "https://x.example.com/any/a.png");
        assertThat(messagesOf(req, "profileImageUrl")).contains("프로필 이미지 주소가 올바르지 않습니다.");
        ReflectionTestUtils.setField(req, "profileImageUrl", "https://k.kakaocdn.net/" + "a".repeat(600));
        assertThat(messagesOf(req, "profileImageUrl")).contains("프로필 이미지 주소가 올바르지 않습니다.");
        ReflectionTestUtils.setField(req, "profileImageUrl", "http://k.kakaocdn.net/dn/abc/img_640x640.jpg");
        assertThat(messagesOf(req, "profileImageUrl")).isEmpty();
        ReflectionTestUtils.setField(req, "profileImageUrl", null);
        assertThat(messagesOf(req, "profileImageUrl")).isEmpty();

        assertThat(messagesOf(req, "pendingToken")).contains("카카오 로그인 정보가 없습니다. 카카오 로그인을 다시 시도해주세요.");
    }

    private Set<String> messagesOf(Object target, String field) {
        Set<ConstraintViolation<Object>> violations = validator.validate(target);
        return violations.stream()
                .filter(v -> v.getPropertyPath().toString().equals(field))
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }
}

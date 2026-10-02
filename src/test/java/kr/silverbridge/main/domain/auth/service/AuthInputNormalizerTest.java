package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 인증 입구 정규화 경계 (AUTH-G09·AUTH-G13·AUTH-G08). */
class AuthInputNormalizerTest {

    @Test
    @DisplayName("이메일 - 앞뒤 공백 제거 + 소문자, '+'는 그대로, null은 null")
    void email() {
        assertThat(AuthInputNormalizer.email("  User+E2E@Example.COM ")).isEqualTo("user+e2e@example.com");
        assertThat(AuthInputNormalizer.email("user@example.com")).isEqualTo("user@example.com");
        assertThat(AuthInputNormalizer.email(null)).isNull();
    }

    @Test
    @DisplayName("이메일 소문자화는 로케일과 무관하다(터키어 로케일의 I → ı 문제 없음)")
    void email_로케일무관() {
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertThat(AuthInputNormalizer.email("ADMIN@SITE.COM")).isEqualTo("admin@site.com");
        } finally {
            java.util.Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("이름 - 앞뒤 공백·제로폭·전각 공백 정리, 가운데 연속 공백은 하나로")
    void name() {
        assertThat(AuthInputNormalizer.name("홍길동 ")).isEqualTo("홍길동");
        assertThat(AuthInputNormalizer.name("​홍길동　")).isEqualTo("홍길동");
        assertThat(AuthInputNormalizer.name("홍  길동")).isEqualTo("홍 길동");
    }

    @Test
    @DisplayName("이름 - 눈에 보이는 글자가 없으면 400(INVALID_INPUT)")
    void name_보이는글자없음() {
        for (String invisible : new String[]{null, "", "   ", "​​", "　", "ㅤ"}) {
            assertThatThrownBy(() -> AuthInputNormalizer.name(invisible))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }
    }

    @Test
    @DisplayName("카카오 대체 이메일 형식 판정 - kakao_{숫자}@kakao.com만(대소문자 무시)")
    void reservedKakaoEmail() {
        assertThat(AuthInputNormalizer.isReservedKakaoEmail("kakao_123456@kakao.com")).isTrue();
        assertThat(AuthInputNormalizer.isReservedKakaoEmail("KAKAO_1@KAKAO.COM")).isTrue();
        assertThat(AuthInputNormalizer.isReservedKakaoEmail("kakao_abc@kakao.com")).isFalse();
        assertThat(AuthInputNormalizer.isReservedKakaoEmail("user@kakao.com")).isFalse();
        assertThat(AuthInputNormalizer.isReservedKakaoEmail("kakao_1@kakao.com.evil")).isFalse();
        assertThat(AuthInputNormalizer.isReservedKakaoEmail(null)).isFalse();
    }
}

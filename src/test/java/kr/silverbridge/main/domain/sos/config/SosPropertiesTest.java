package kr.silverbridge.main.domain.sos.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SosProperties 단위 테스트 - 기본값 10초, 설정 반영, 범위 밖 값 방어. */
class SosPropertiesTest {

    @Test
    @DisplayName("기본값은 10초")
    void 기본값_10초() {
        assertThat(new SosProperties().effectiveNotifyCooldownSeconds()).isEqualTo(10);
    }

    @Test
    @DisplayName("sos.notify-cooldown-seconds 키가 바인딩된다")
    void 키_바인딩() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("sos.notify-cooldown-seconds", "45")));

        SosProperties props = binder.bind("sos", SosProperties.class).get();

        assertThat(props.effectiveNotifyCooldownSeconds()).isEqualTo(45);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 301, 100000})
    @DisplayName("범위(1~300) 밖의 값은 기본 10초로 대체")
    void 범위밖_기본값(int value) {
        SosProperties props = new SosProperties();
        props.setNotifyCooldownSeconds(value);

        assertThat(props.effectiveNotifyCooldownSeconds()).isEqualTo(10);
    }

    /**
     * 점검 M-1(2026-10-05): 범위 밖 "정수"만 기본값으로 대체된다. 빈 값(env 파일의 {@code KEY=})·비숫자는
     * 대체되지 않고 바인딩 단계에서 기동 실패(fail-fast)한다 - 다른 설정 클래스와 같은 동작이다.
     * 조용히 기본값으로 삼키도록 바꾸려면 문서(fix-sos-notify-cooldown §2, 정책 파일)와 함께 의도를 바꿀 것.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "abc", "10s", "5.5", "99999999999"})
    @DisplayName("빈 값·비숫자·정수 범위 초과 문자열은 기본값으로 대체되지 않고 바인딩 실패(기동 실패)")
    void 비정수_바인딩_실패(String raw) {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("sos.notify-cooldown-seconds", raw)));

        assertThatThrownBy(() -> binder.bind("sos", SosProperties.class))
                .isInstanceOf(BindException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 300})
    @DisplayName("경계 1·300은 그대로 허용")
    void 경계_허용(int value) {
        SosProperties props = new SosProperties();
        props.setNotifyCooldownSeconds(value);

        assertThat(props.effectiveNotifyCooldownSeconds()).isEqualTo(value);
    }
}

package kr.silverbridge.main.domain.sos.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 300})
    @DisplayName("경계 1·300은 그대로 허용")
    void 경계_허용(int value) {
        SosProperties props = new SosProperties();
        props.setNotifyCooldownSeconds(value);

        assertThat(props.effectiveNotifyCooldownSeconds()).isEqualTo(value);
    }
}

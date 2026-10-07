package kr.silverbridge.main.domain.camera.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** 권장 송출 fps 기본값(10, 2026-10-07)이 yaml과 {@code @Value} 폴백에서 어긋나지 않게 고정한다. */
class CameraRecommendedFpsDefaultTest {

    @Test
    @DisplayName("application.yaml 기본값은 10이고 환경변수 이름은 CAMERA_RECOMMENDED_FPS다")
    void yaml기본값() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties props = yaml.getObject();

        assertThat(props).isNotNull();
        assertThat(props.getProperty("camera.recommended-fps"))
                .isEqualTo("${CAMERA_RECOMMENDED_FPS:10}");
    }

    @Test
    @DisplayName("CameraService @Value 폴백도 10이다(yaml과 어긋나면 안 된다)")
    void 서비스폴백() throws NoSuchFieldException {
        Value value = CameraService.class.getDeclaredField("recommendedFps").getAnnotation(Value.class);

        assertThat(value.value()).isEqualTo("${camera.recommended-fps:10}");
    }
}

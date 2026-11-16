package kr.silverbridge.main.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** 최종 QA CHAT-N33 (2026-11-16): 요청 전체 상한은 12MB, 파일 하나 상한은 10MB 그대로. */
class MultipartLimitConfigTest {

    private static Properties appYaml() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yaml"));
        return factory.getObject();
    }

    @Test
    @DisplayName("챗봇 음성 답변 최대치(음성 5MB + 사진 1MB x 5장 + 머리글)가 들어가도록 요청 전체 12MB, 파일 하나 10MB")
    void multipart_limits() {
        Properties props = appYaml();
        assertThat(props.getProperty("spring.servlet.multipart.max-request-size")).isEqualTo("12MB");
        assertThat(props.getProperty("spring.servlet.multipart.max-file-size")).isEqualTo("10MB");
    }
}

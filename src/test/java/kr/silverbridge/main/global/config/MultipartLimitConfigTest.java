package kr.silverbridge.main.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

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

    @Test
    @DisplayName("말벗 음성 대답 최대치(음성 5MB + 사진 1MB x 5장)에 머리글 여유 1MB를 더해도 요청 상한 안, 기존 10MB는 넘는다 (11/16 점검 L-2)")
    void max_payload_fits_request_limit() {
        Properties props = appYaml();
        long request = DataSize.parse(props.getProperty("spring.servlet.multipart.max-request-size")).toBytes();
        long file = DataSize.parse(props.getProperty("spring.servlet.multipart.max-file-size")).toBytes();
        long maxPayload = DataSize.ofMegabytes(5).toBytes() + 5 * DataSize.ofMegabytes(1).toBytes();
        long headroom = DataSize.ofMegabytes(1).toBytes(); // 파일 6개의 멀티파트 머리글·경계 문자열 여유

        assertThat(maxPayload + headroom).isLessThanOrEqualTo(request);
        assertThat(maxPayload).as("10MB 였다면 머리글 때문에 넘었다").isGreaterThanOrEqualTo(DataSize.ofMegabytes(10).toBytes());
        assertThat(file).as("파일 하나 상한은 요청 상한보다 크지 않다").isLessThanOrEqualTo(request);
        // 앞단 nginx client_max_body_size(13m)는 이 값보다 커야 한다 - gosky devdmu·api.devdmu 둘 다 (policy-medication)
        assertThat(DataSize.ofMegabytes(13).toBytes()).isGreaterThan(request);
    }
}

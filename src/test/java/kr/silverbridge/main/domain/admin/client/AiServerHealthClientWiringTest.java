package kr.silverbridge.main.domain.admin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.chat.config.ChatRelayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Spring 컨텍스트에서 빈이 만들어지는지 고정한다(2026-10-27). 단위 테스트는 생성자를 직접 불러서
 * "생성자가 둘인데 Spring이 고를 쪽이 없다"는 기동 실패를 잡지 못했다 - 배포 서버가 재시작을 반복했다.
 */
@SpringJUnitConfig(AiServerHealthClientWiringTest.Config.class)
class AiServerHealthClientWiringTest {

    @Configuration
    @Import(AiServerHealthClient.class)
    static class Config {
        @Bean
        ChatRelayProperties chatRelayProperties() {
            return new ChatRelayProperties();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    private AiServerHealthClient client;

    @Test
    @DisplayName("Spring이 공개 생성자로 빈을 만든다")
    void beanIsCreated() {
        assertThat(client).isNotNull();
    }
}

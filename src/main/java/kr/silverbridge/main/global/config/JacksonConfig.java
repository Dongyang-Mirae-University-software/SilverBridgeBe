package kr.silverbridge.main.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 4는 웹 스택 기본 Jackson을 Jackson 3로 이동하면서
 * Jackson 2(com.fasterxml) ObjectMapper 빈을 더 이상 자동 등록하지 않는다.
 * Jackson 2 ObjectMapper 를 주입받는 코드를 위해 명시적으로 제공한다.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        // findAndRegisterModules: classpath의 Jackson 모듈 자동 등록
        // (이전 Boot 자동설정 동작과 동일하게 유지)
        return new ObjectMapper().findAndRegisterModules();
    }
}
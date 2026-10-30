package kr.silverbridge.main.domain.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 챗 전송 본문 상한 필터 등록 - 챗 전송 두 경로에만 건다. 순서를 지정하지 않아 보안 필터 체인 뒤에 돈다.
 */
@Configuration
public class ChatRequestSizeFilterConfig {

    @Bean
    public FilterRegistrationBean<ChatRequestSizeFilter> chatRequestSizeFilter(ObjectMapper objectMapper,
                                                                               ChatRelayProperties properties) {
        FilterRegistrationBean<ChatRequestSizeFilter> registration = new FilterRegistrationBean<>(
                new ChatRequestSizeFilter(objectMapper, properties.getMaxRequestBytes()));
        registration.addUrlPatterns("/api/guardian/chat", "/api/ward/chat");
        registration.setName("chatRequestSizeFilter");
        return registration;
    }
}

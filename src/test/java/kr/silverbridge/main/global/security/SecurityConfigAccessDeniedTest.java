package kr.silverbridge.main.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.global.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SecurityConfig 의 URL 규칙 위반 응답 검증 (ADMIN-G01 / XCUT-G09).
 * 비ADMIN 의 /api/admin/** 는 빈 본문이 아니라 JSON 403(@PreAuthorize 경로와 같은 문구), 미인증은 JSON 401 로 구분된다.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        SecurityConfigAccessDeniedTest.TestConfig.class,
        SecurityConfig.class
})
@TestPropertySource(properties = "app.cors.allowed-origins=http://localhost:3000")
class SecurityConfigAccessDeniedTest {

    @Configuration
    @EnableWebMvc
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/admin/probe")
        String admin() {
            return "ok";
        }

        @GetMapping("/api/user/probe")
        String user() {
            return "ok";
        }
    }

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;
    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("비ADMIN(GUARDIAN)의 /api/admin/** → JSON 403 + 접근 권한 없음 + code")
    void nonAdmin_403_json() throws Exception {
        mockMvc.perform(get("/api/admin/probe").with(user("u1").roles("GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("접근 권한이 없습니다."))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("미인증의 /api/admin/** → 403이 아니라 JSON 401 + code")
    void unauthenticated_401_json() throws Exception {
        mockMvc.perform(get("/api/admin/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."))
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    }

    @Test
    @DisplayName("ADMIN → 통과")
    void admin_200() throws Exception {
        mockMvc.perform(get("/api/admin/probe").with(user("a1").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("일반 보호 경로: 인증 없으면 401, 인증되면 통과")
    void protectedPath() throws Exception {
        mockMvc.perform(get("/api/user/probe")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/user/probe").with(user("u1").roles("WARD"))).andExpect(status().isOk());
    }
}

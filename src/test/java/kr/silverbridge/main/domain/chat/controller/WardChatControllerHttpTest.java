package kr.silverbridge.main.domain.chat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.chat.service.ChatRelayService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 챗 중계 응답의 실제 HTTP 직렬화(2026-10-08). Spring Boot 4 웹 변환기는 Jackson 3라
 * Jackson 2 JsonNode 를 반환하면 bean getter(array·bigDecimal·nodeType…)가 내려간다.
 * 서비스가 돌려주는 일반 객체(Map·List)가 JSON 으로 그대로 나가는지 실제 변환기로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class WardChatControllerHttpTest {

    private static final ObjectMapper JACKSON2 = new ObjectMapper();

    @Mock private ChatRelayService chatRelayService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("aB3x9Z", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new WardChatController(chatRelayService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    /** 서비스가 AI 응답을 변환해 돌려주는 값과 같은 형태(Jackson 2 로 일반 객체화). */
    private static Object plain(String json) throws Exception {
        return JACKSON2.convertValue(JACKSON2.readTree(json), Object.class);
    }

    @Test
    @DisplayName("전송: data.reply·riskLevel 이 문자열로, 숫자·null·배열·중첩이 그대로 내려간다")
    void sendSerializesPlainJson() throws Exception {
        when(chatRelayService.send(eq("aB3x9Z"), any())).thenReturn(plain("""
                {"reply":"물을 충분히 드세요","riskLevel":"low","count":3,"ratio":0.5,"none":null,
                 "list":["a","b"],"toolData":{"hospitals":[{"name":"x","dist":1.2}]}}"""));

        mvc().perform(post("/api/ward/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reply").value("물을 충분히 드세요"))
                .andExpect(jsonPath("$.data.riskLevel").value("low"))
                .andExpect(jsonPath("$.data.count").value(3))
                .andExpect(jsonPath("$.data.ratio").value(0.5))
                .andExpect(jsonPath("$.data.none").doesNotExist())
                .andExpect(jsonPath("$.data.list[1]").value("b"))
                .andExpect(jsonPath("$.data.toolData.hospitals[0].dist").value(1.2))
                .andExpect(jsonPath("$.data.nodeType").doesNotExist())
                .andExpect(jsonPath("$.data.array").doesNotExist());
    }

    @Test
    @DisplayName("기록 목록: 배열로 내려가고, 비어 있으면 빈 배열이다")
    void logsSerializeAsArray() throws Exception {
        when(chatRelayService.logs("aB3x9Z")).thenReturn(plain("[{\"id\":7,\"reply\":\"r\"}]"));
        mvc().perform(get("/api/ward/chat/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(7))
                .andExpect(jsonPath("$.data[0].reply").value("r"));

        when(chatRelayService.logs("aB3x9Z")).thenReturn(plain("[]"));
        mvc().perform(get("/api/ward/chat/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("기록 상세: 객체로 내려간다")
    void logDetailSerializesAsObject() throws Exception {
        when(chatRelayService.logDetail(eq("aB3x9Z"), anyLong())).thenReturn(plain("{\"id\":9,\"reply\":\"r\"}"));
        mvc().perform(get("/api/ward/chat/logs/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(9))
                .andExpect(jsonPath("$.data.reply").value("r"));
    }
}

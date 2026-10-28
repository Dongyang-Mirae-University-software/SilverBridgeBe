package kr.silverbridge.main.domain.admin.controller;

import kr.silverbridge.main.domain.admin.dto.AdminAiChatStatsResponse;
import kr.silverbridge.main.domain.admin.dto.AdminAiChatStatsResponse.ByRole;
import kr.silverbridge.main.domain.admin.dto.AdminAiChatStatsResponse.ByRoute;
import kr.silverbridge.main.domain.admin.dto.AdminAiChatStatsResponse.Daily;
import kr.silverbridge.main.domain.admin.service.AdminAiChatStatsService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 챗 집계 응답의 실제 HTTP 직렬화(2026-10-28). 관리자 웹은 계약 문서만 보고 붙으므로 칸 이름·null 칸 유지·
 * 오류 code를 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class AdminAiStatsControllerHttpTest {

    @Mock private AdminAiChatStatsService adminAiChatStatsService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("ADM001", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new AdminAiStatsController(adminAiChatStatsService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("칸 이름·값과 토큰의 관리자 ID·기간이 서비스로 간다, 모르는 개수는 0이 아니라 null 칸으로 남는다")
    void serializes() throws Exception {
        when(adminAiChatStatsService.getChatStats("ADM001", "2026-10-27", "2026-10-28")).thenReturn(
                new AdminAiChatStatsResponse("2026-10-27", "2026-10-28", 9L, 3L,
                        new ByRole(5L, 3L, 1L), new ByRoute(4L, 3L, 2L, 0L),
                        1L, null, 0L,
                        List.of(new Daily("2026-10-27", 7L), new Daily("2026-10-28", 2L)),
                        "2026-10-28T11:03:27+09:00"));

        mvc().perform(get("/api/admin/ai-stats/chat").param("from", "2026-10-27").param("to", "2026-10-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.from").value("2026-10-27"))
                .andExpect(jsonPath("$.data.to").value("2026-10-28"))
                .andExpect(jsonPath("$.data.totalChats").value(9))
                .andExpect(jsonPath("$.data.users").value(3))
                .andExpect(jsonPath("$.data.byRole.guardian").value(5))
                .andExpect(jsonPath("$.data.byRole.ward").value(3))
                .andExpect(jsonPath("$.data.byRole.unknown").value(1))
                .andExpect(jsonPath("$.data.byRoute.reservation").value(4))
                .andExpect(jsonPath("$.data.byRoute.symptom").value(3))
                .andExpect(jsonPath("$.data.byRoute.general").value(2))
                .andExpect(jsonPath("$.data.byRoute.unknown").value(0))
                .andExpect(jsonPath("$.data.emergencyGuidance").value(1))
                .andExpect(jsonPath("$.data", hasKey("reservationCompleted")))
                .andExpect(jsonPath("$.data.reservationCompleted").value(nullValue()))
                .andExpect(jsonPath("$.data.fallbackReplies").value(0))
                .andExpect(jsonPath("$.data.daily[0].date").value("2026-10-27"))
                .andExpect(jsonPath("$.data.daily[1].totalChats").value(2))
                .andExpect(jsonPath("$.data.generatedAt").value("2026-10-28T11:03:27+09:00"))
                .andExpect(jsonPath("$.data", not(hasKey("userId"))));
    }

    @Test
    @DisplayName("기간 오류는 400 AI_STATS_INVALID_RANGE, AI 장애는 503 AI_STATS_UNAVAILABLE, 지연은 504 AI_STATS_TIMEOUT")
    void errors() throws Exception {
        when(adminAiChatStatsService.getChatStats(eq("ADM001"), eq("bad"), any()))
                .thenThrow(new CustomException(ErrorCode.AI_STATS_INVALID_RANGE));
        when(adminAiChatStatsService.getChatStats(eq("ADM001"), eq("2026-10-01"), any()))
                .thenThrow(new CustomException(ErrorCode.AI_STATS_UNAVAILABLE));
        when(adminAiChatStatsService.getChatStats(eq("ADM001"), eq("2026-10-02"), any()))
                .thenThrow(new CustomException(ErrorCode.AI_STATS_TIMEOUT));

        mvc().perform(get("/api/admin/ai-stats/chat").param("from", "bad").param("to", "2026-10-28"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AI_STATS_INVALID_RANGE"));
        mvc().perform(get("/api/admin/ai-stats/chat").param("from", "2026-10-01").param("to", "2026-10-28"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_STATS_UNAVAILABLE"));
        mvc().perform(get("/api/admin/ai-stats/chat").param("from", "2026-10-02").param("to", "2026-10-28"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("AI_STATS_TIMEOUT"));
    }
}

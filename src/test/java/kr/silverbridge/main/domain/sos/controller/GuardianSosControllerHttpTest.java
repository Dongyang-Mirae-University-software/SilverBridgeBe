package kr.silverbridge.main.domain.sos.controller;

import kr.silverbridge.main.domain.sos.dto.SosHistoryItem;
import kr.silverbridge.main.domain.sos.dto.SosHistoryPage;
import kr.silverbridge.main.domain.sos.entity.SosTriggerType;
import kr.silverbridge.main.domain.sos.service.GuardianSosService;
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 보호자 SOS 이력의 {@code group} 파라미터 분기와 실제 HTTP 직렬화(2026-10-30).
 *
 * <p>파라미터가 없으면 기존 응답 그대로여야 한다 - 묶음 칸(pressCount·firstTriggeredAt·lastTriggeredAt)이
 * JSON에 나타나지 않는지 실제 변환기로 확인한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class GuardianSosControllerHttpTest {

    private static final String GUARDIAN_ID = "GD0001";
    private static final OffsetDateTime AT = OffsetDateTime.of(2026, 10, 5, 15, 21, 30, 0, ZoneOffset.ofHours(9));

    @Mock private GuardianSosService guardianSosService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(GUARDIAN_ID, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new GuardianSosController(guardianSosService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    private static SosHistoryPage page(SosHistoryItem item) {
        return new SosHistoryPage(List.of(item), 0, 20, 1, 1, true, SosHistoryPage.Counts.of(1, 0));
    }

    @Test
    @DisplayName("group 생략 → 기존 조회, 묶음 칸은 JSON에 없다")
    void 파라미터없음_기존응답() throws Exception {
        when(guardianSosService.getHistory(eq(GUARDIAN_ID), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(page(new SosHistoryItem(1L, "WD0001", "김영희", AT, "거실",
                        SosTriggerType.SOS_BUTTON, null, null, null)));

        mvc().perform(get("/api/guardian/sos/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].sosEventId").value(1))
                .andExpect(jsonPath("$.data.content[0].location").value("거실"))
                .andExpect(jsonPath("$.data.content[0].pressCount").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].firstTriggeredAt").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].lastTriggeredAt").doesNotExist());
        verify(guardianSosService, never()).getGroupedHistory(eq(GUARDIAN_ID), isNull(), isNull(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("group=true → 묶음 조회, pressCount·첫/마지막 시각이 내려간다")
    void 묶음조회() throws Exception {
        when(guardianSosService.getGroupedHistory(eq(GUARDIAN_ID), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(page(new SosHistoryItem(3L, "WD0001", "김영희", AT, "거실",
                        SosTriggerType.SOS_BUTTON, 2L, AT.minusSeconds(40), AT)));

        mvc().perform(get("/api/guardian/sos/history").param("group", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].sosEventId").value(3))
                .andExpect(jsonPath("$.data.content[0].pressCount").value(2))
                .andExpect(jsonPath("$.data.content[0].firstTriggeredAt").exists())
                .andExpect(jsonPath("$.data.content[0].lastTriggeredAt").exists())
                .andExpect(jsonPath("$.data.counts.all").value(1));
        verify(guardianSosService, never()).getHistory(eq(GUARDIAN_ID), isNull(), isNull(), anyInt(), anyInt());
    }
}

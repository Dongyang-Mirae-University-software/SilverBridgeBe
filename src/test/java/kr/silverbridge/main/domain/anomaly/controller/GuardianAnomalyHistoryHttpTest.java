package kr.silverbridge.main.domain.anomaly.controller;

import kr.silverbridge.main.domain.anomaly.dto.AnomalyTypeFilter;
import kr.silverbridge.main.domain.anomaly.dto.GuardianAnomalyHistorySummary;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipAccessService;
import kr.silverbridge.main.domain.anomaly.service.GuardianAnomalyService;
import kr.silverbridge.main.domain.anomaly.service.GuardianAnomalySettingService;
import kr.silverbridge.main.global.exception.GlobalExceptionHandler;
import kr.silverbridge.main.global.response.PageResponse;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 보호자 이력 {@code type} 파라미터의 HTTP 바인딩·400 응답과 요약 경로 고정(2026-10-07). */
@ExtendWith(MockitoExtension.class)
class GuardianAnomalyHistoryHttpTest {

    @Mock private GuardianAnomalyService service;
    @Mock private GuardianAnomalySettingService settingService;
    @Mock private AnomalyClipAccessService clipAccessService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new GuardianAnomalyController(service, settingService, clipAccessService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("GD0001", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("type=FIRE는 서비스에 FIRE로 전달된다")
    void typeBinds() throws Exception {
        when(service.getHistory(eq("GD0001"), any(), eq(AnomalyTypeFilter.FIRE), eq(0), eq(20)))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));

        mockMvc.perform(get("/api/guardian/anomaly/history").param("type", "FIRE"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("type을 생략하면 null(전체)로 전달된다")
    void typeOmitted() throws Exception {
        when(service.getHistory(eq("GD0001"), any(), eq(null), eq(0), eq(20)))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));

        mockMvc.perform(get("/api/guardian/anomaly/history")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("허용 값이 아닌 type(NORMAL·UNKNOWN·SMOKE·소문자·임의 문자열)은 400이고 서비스를 부르지 않는다")
    void invalidTypeIs400() throws Exception {
        for (String bad : List.of("NORMAL", "UNKNOWN", "SMOKE", "fire", "abc")) {
            mockMvc.perform(get("/api/guardian/anomaly/history").param("type", bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }
        verify(service, never()).getHistory(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("요약 경로는 history/summary로 응답한다")
    void summaryRoute() throws Exception {
        when(service.getHistorySummary("GD0001", "WD0001")).thenReturn(
                new GuardianAnomalyHistorySummary(10, 2, 1, 3, new GuardianAnomalyHistorySummary.ByType(6, 3, 1)));

        mockMvc.perform(get("/api/guardian/anomaly/history/summary").param("wardId", "WD0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(10))
                .andExpect(jsonPath("$.data.pendingCount").value(2))
                .andExpect(jsonPath("$.data.conflictedCount").value(1))
                .andExpect(jsonPath("$.data.needsReviewCount").value(3))
                .andExpect(jsonPath("$.data.byType.fire").value(6))
                .andExpect(jsonPath("$.data.byType.weapon").value(1));
    }
}

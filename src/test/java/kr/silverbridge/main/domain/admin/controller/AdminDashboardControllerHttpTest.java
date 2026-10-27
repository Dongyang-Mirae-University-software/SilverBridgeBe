package kr.silverbridge.main.domain.admin.controller;

import kr.silverbridge.main.domain.admin.dto.AdminAiStatusResponse;
import kr.silverbridge.main.domain.admin.dto.AdminOperationDashboardResponse;
import kr.silverbridge.main.domain.admin.dto.AdminSafetyDashboardResponse;
import kr.silverbridge.main.domain.admin.dto.AiServiceState;
import kr.silverbridge.main.domain.admin.service.AdminAiStatusService;
import kr.silverbridge.main.domain.admin.service.AdminDashboardService;
import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalySummaryResponse;
import kr.silverbridge.main.global.enums.DetectedType;
import kr.silverbridge.main.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 대시보드 응답의 실제 HTTP 직렬화(2026-10-27 보강). 관리자 웹은 계약 문서만 보고 붙으므로
 * ① 기존 칸 이름이 그대로인지(하위 호환) ② 새 칸이 들어갔는지 ③ "모르는 값 = null"이 JSON에서 빠지지 않고 null로 가는지를 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class AdminDashboardControllerHttpTest {

    private static final OffsetDateTime AT = OffsetDateTime.of(2026, 10, 27, 14, 5, 0, 0, ZoneOffset.ofHours(9));

    @Mock private AdminDashboardService adminDashboardService;
    @Mock private AdminAiStatusService adminAiStatusService;

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new AdminDashboardController(adminDashboardService, adminAiStatusService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("안전 현황: 기존 칸 유지 + 전달 실패 알림·AI 신뢰도·generatedAt, 모르는 값은 null 칸으로")
    void safety() throws Exception {
        when(adminDashboardService.getSafetyDashboard()).thenReturn(new AdminSafetyDashboardResponse(
                false, 0, 3L, null,
                new AdminSafetyDashboardResponse.SafetyEvents(null, 1L, 2L, 3L, 4L, 5L),
                new AdminSafetyDashboardResponse.TodayAnomaly(2L,
                        List.of(new AdminSafetyDashboardResponse.TypeCount(DetectedType.FIRE, 2L)),
                        new AdminSafetyDashboardResponse.ReviewCount(2L, 0L, 0L, 0L),
                        new AdminAnomalySummaryResponse.AiConfidence(null, 0L)),
                AT));

        mvc().perform(get("/api/admin/dashboard/safety"))
                .andExpect(status().isOk())
                // 기존 칸(하위 호환)
                .andExpect(jsonPath("$.data.aiConnected").value(false))
                .andExpect(jsonPath("$.data.subscribedSessions").value(0))
                .andExpect(jsonPath("$.data.totalCameras").value(3))
                .andExpect(jsonPath("$.data", hasKey("streamingCameras")))
                .andExpect(jsonPath("$.data.streamingCameras").value(nullValue()))
                .andExpect(jsonPath("$.data.safetyEvents", hasKey("disconnectedCameras")))
                .andExpect(jsonPath("$.data.safetyEvents.disconnectedCameras").value(nullValue()))
                .andExpect(jsonPath("$.data.safetyEvents.wardsWithoutGuardian").value(1))
                .andExpect(jsonPath("$.data.safetyEvents.wardsWithoutReachableGuardian").value(2))
                .andExpect(jsonPath("$.data.safetyEvents.wardsWithoutCamera").value(3))
                .andExpect(jsonPath("$.data.safetyEvents.stalePendingConnections").value(4))
                .andExpect(jsonPath("$.data.todayAnomaly.total").value(2))
                .andExpect(jsonPath("$.data.todayAnomaly.byType[0].detectedType").value("FIRE"))
                .andExpect(jsonPath("$.data.todayAnomaly.review.pending").value(2))
                .andExpect(jsonPath("$.data.todayAnomaly.review.conflicted").value(0))
                // 새 칸
                .andExpect(jsonPath("$.data.safetyEvents.todayFailedNotifications").value(5))
                .andExpect(jsonPath("$.data.todayAnomaly.aiConfidence", hasKey("average")))
                .andExpect(jsonPath("$.data.todayAnomaly.aiConfidence.average").value(nullValue()))
                .andExpect(jsonPath("$.data.todayAnomaly.aiConfidence.basis").value(0))
                .andExpect(jsonPath("$.data.generatedAt").value("2026-10-27T14:05:00+09:00"));
    }

    @Test
    @DisplayName("운영 현황: 기존 칸 유지 + cameras.maxTotal(꺼지면 null 칸)·generatedAt")
    void operation() throws Exception {
        when(adminDashboardService.getOperationDashboard()).thenReturn(new AdminOperationDashboardResponse(
                13L, 2L,
                new AdminOperationDashboardResponse.MemberComposition(6L, 7L),
                new AdminOperationDashboardResponse.Cameras(2L, 1L, null),
                4L,
                new AdminOperationDashboardResponse.UnansweredInquiries(0L, null),
                List.of(new AdminOperationDashboardResponse.SignupPoint(LocalDate.of(2026, 10, 27), 1L)),
                new AdminOperationDashboardResponse.PendingItems(4L, 1L, 3L),
                AT));

        mvc().perform(get("/api/admin/dashboard/operation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalUsers").value(13))
                .andExpect(jsonPath("$.data.newUsersToday").value(2))
                .andExpect(jsonPath("$.data.memberComposition.wards").value(6))
                .andExpect(jsonPath("$.data.cameras.registered").value(2))
                .andExpect(jsonPath("$.data.cameras.wards").value(1))
                .andExpect(jsonPath("$.data.cameras", hasKey("maxTotal")))
                .andExpect(jsonPath("$.data.cameras.maxTotal").value(nullValue()))
                .andExpect(jsonPath("$.data.pendingConnections").value(4))
                .andExpect(jsonPath("$.data.unansweredInquiries.longestWaitingHours").value(nullValue()))
                .andExpect(jsonPath("$.data.signupTrend[0].date").value("2026-10-27"))
                .andExpect(jsonPath("$.data.pendingItems.announcementDrafts").value(3))
                .andExpect(jsonPath("$.data.generatedAt").value("2026-10-27T14:05:00+09:00"));
    }

    @Test
    @DisplayName("AI 상태: 확인 불가는 status 칸이 빠지지 않고 null로 간다, 끊김은 DISCONNECTED")
    void aiStatus() throws Exception {
        when(adminAiStatusService.getAiStatus()).thenReturn(new AdminAiStatusResponse(
                new AdminAiStatusResponse.Item(AiServiceState.DISCONNECTED, AT),
                new AdminAiStatusResponse.Item(null, AT),
                AT));

        mvc().perform(get("/api/admin/dashboard/ai-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.anomalyDetection.status").value("DISCONNECTED"))
                .andExpect(jsonPath("$.data.anomalyDetection.checkedAt").value("2026-10-27T14:05:00+09:00"))
                .andExpect(jsonPath("$.data.chatbot", hasKey("status")))
                .andExpect(jsonPath("$.data.chatbot.status").value(nullValue()))
                .andExpect(jsonPath("$.data.chatbot.checkedAt").value("2026-10-27T14:05:00+09:00"))
                .andExpect(jsonPath("$.data.generatedAt").value("2026-10-27T14:05:00+09:00"));
    }
}

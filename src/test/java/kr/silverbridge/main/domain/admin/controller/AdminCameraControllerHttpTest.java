package kr.silverbridge.main.domain.admin.controller;

import kr.silverbridge.main.domain.admin.dto.AdminCameraItem;
import kr.silverbridge.main.domain.admin.dto.AdminCameraLiveStatus;
import kr.silverbridge.main.domain.admin.dto.AdminCameraStatusFilter;
import kr.silverbridge.main.domain.admin.dto.AdminCameraSummaryResponse;
import kr.silverbridge.main.domain.admin.service.AdminCameraService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 디바이스 관리 응답의 실제 HTTP 직렬화(2026-10-23). 관리자 웹은 계약 문서만 보고 붙으므로
 * "모르는 값 = null 칸"이 JSON에서 빠지지 않고 null로 내려가는지, 송출 기기 식별값·영상 주소가 없는지를 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class AdminCameraControllerHttpTest {

    @Mock private AdminCameraService adminCameraService;

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
        return MockMvcBuilders.standaloneSetup(new AdminCameraController(adminCameraService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static AdminCameraItem item(AdminCameraLiveStatus status) {
        return new AdminCameraItem(12L, "ward_a1B2c3D4e5F6g7H8", "거실", "W00012", "김영자",
                "서울 강남구 역삼로 123", true,
                OffsetDateTime.of(2026, 10, 14, 13, 5, 0, 0, ZoneOffset.ofHours(9)), status);
    }

    @Test
    @DisplayName("목록: 칸 이름·값, status·q·page·size와 토큰의 관리자 ID가 서비스로 간다")
    void listSerializes() throws Exception {
        when(adminCameraService.getCameras(eq("ADM001"), eq(AdminCameraStatusFilter.LIVE), eq("역삼"), eq(0), eq(10)))
                .thenReturn(new PageResponse<>(List.of(item(AdminCameraLiveStatus.LIVE)), 0, 10, 1, 1, true));

        mvc().perform(get("/api/admin/camera").param("status", "LIVE").param("q", "역삼").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].cameraId").value(12))
                .andExpect(jsonPath("$.data.content[0].sessionId").value("ward_a1B2c3D4e5F6g7H8"))
                .andExpect(jsonPath("$.data.content[0].room").value("거실"))
                .andExpect(jsonPath("$.data.content[0].wardName").value("김영자"))
                .andExpect(jsonPath("$.data.content[0].wardAddress").value("서울 강남구 역삼로 123"))
                .andExpect(jsonPath("$.data.content[0].isActive").value(true))
                .andExpect(jsonPath("$.data.content[0].registeredAt").value("2026-10-14T13:05:00+09:00"))
                .andExpect(jsonPath("$.data.content[0].liveStatus").value("LIVE"))
                .andExpect(jsonPath("$.data.content[0]", not(hasKey("deviceId"))))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("목록: 상태를 모르면 liveStatus 칸이 빠지지 않고 null로 내려간다")
    void unknownStatusIsExplicitNull() throws Exception {
        when(adminCameraService.getCameras(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(item(null)), 0, 20, 1, 1, true));

        mvc().perform(get("/api/admin/camera"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0]", hasKey("liveStatus")))
                .andExpect(jsonPath("$.data.content[0].liveStatus").value(nullValue()));
    }

    @Test
    @DisplayName("목록: 상태를 모를 때 LIVE·OFFLINE 필터는 503 CAMERA_LIVE_STATUS_UNKNOWN")
    void unknownFilterIs503() throws Exception {
        when(adminCameraService.getCameras(any(), eq(AdminCameraStatusFilter.OFFLINE), any(), anyInt(), anyInt()))
                .thenThrow(new CustomException(ErrorCode.CAMERA_LIVE_STATUS_UNKNOWN));

        mvc().perform(get("/api/admin/camera").param("status", "OFFLINE"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CAMERA_LIVE_STATUS_UNKNOWN"));
    }

    @Test
    @DisplayName("목록: 정해진 값 밖의 status는 빈 목록이 아니라 400(서비스를 부르지 않음)")
    void invalidStatusIs400() throws Exception {
        mvc().perform(get("/api/admin/camera").param("status", "ISSUE"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(adminCameraService);
    }

    @Test
    @DisplayName("요약: 모르는 값(live·offline·maxTotal)은 0이 아니라 null 칸으로 내려간다")
    void summaryNulls() throws Exception {
        when(adminCameraService.getSummary())
                .thenReturn(new AdminCameraSummaryResponse(false, 3, null, null, null, 34, 31));

        mvc().perform(get("/api/admin/camera/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aiConnected").value(false))
                .andExpect(jsonPath("$.data.registered").value(3))
                .andExpect(jsonPath("$.data", hasKey("live")))
                .andExpect(jsonPath("$.data.live").value(nullValue()))
                .andExpect(jsonPath("$.data.offline").value(nullValue()))
                .andExpect(jsonPath("$.data.maxTotal").value(nullValue()))
                .andExpect(jsonPath("$.data.totalWards").value(34))
                .andExpect(jsonPath("$.data.wardsWithoutCamera").value(31));
    }
}

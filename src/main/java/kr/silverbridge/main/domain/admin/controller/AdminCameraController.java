package kr.silverbridge.main.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.admin.dto.AdminCameraItem;
import kr.silverbridge.main.domain.admin.dto.AdminCameraStatusFilter;
import kr.silverbridge.main.domain.admin.dto.AdminCameraSummaryResponse;
import kr.silverbridge.main.domain.admin.service.AdminCameraService;
import kr.silverbridge.main.global.response.ApiResponse;
import kr.silverbridge.main.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 디바이스(카메라) 관리 API - 조회 전용(2026-10-23).
 *
 * <p>인가는 {@code SecurityConfig}의 {@code /api/admin/**} 경로 규칙과 클래스 레벨 {@code @PreAuthorize}가
 * 이중으로 담당한다. 조회라 감사 로그는 남기지 않는다. 카메라 삭제·점검 요청은 만들지 않았다 -
 * 쓰기 매핑을 더하려면 {@code AdminCameraControllerSecurityTest}의 고정부터 정책과 함께 바꿀 것.</p>
 */
@Tag(name = "관리자 - 디바이스 관리")
@RestController
@RequestMapping("/api/admin/camera")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCameraController {

    private final AdminCameraService adminCameraService;

    @Operation(summary = "카메라 목록",
            description = """
                    등록된 카메라를 등록 최신순으로 조회합니다. 탈퇴 처리 중인 피보호자의 카메라는 빠집니다.

                    [liveStatus]
                    - LIVE: AI 서버가 영상을 받고 있고 서버가 감시(구독) 중
                    - OFFLINE: AI 서버 목록에 없음(송출 꺼짐·끊김)
                    - null: AI 연결이 끊겨 지금 확인할 수 없음. "끊김"으로 표시하지 마세요

                    [필터 - 모두 선택]
                    - status: ALL · LIVE · OFFLINE (생략 시 ALL). 잘못된 값은 400
                    - 상태를 확인할 수 없을 때 LIVE·OFFLINE 필터는 빈 목록이 아니라 503 CAMERA_LIVE_STATUS_UNKNOWN
                    - q: 주소·설치 위치·피보호자 이름·세션 ID 부분 일치(공백·대소문자 무시)

                    isActive=false는 사용자가 화면에서 숨긴 카메라이며 감지·알림은 계속됩니다.
                    영상·정지 화면·클립과 송출 기기 식별값은 싣지 않습니다(관리자 열람 불가).
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 status 값", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "AI 연결이 끊겨 LIVE·OFFLINE 필터를 판정할 수 없음(CAMERA_LIVE_STATUS_UNKNOWN)", content = @Content)
    })
    @GetMapping
    public ApiResponse<PageResponse<AdminCameraItem>> getCameras(
            @AuthenticationPrincipal String adminId,
            @Parameter(description = "상태 (생략 시 ALL)")
            @RequestParam(required = false) AdminCameraStatusFilter status,
            @Parameter(description = "검색어 - 주소·설치 위치·피보호자 이름·세션 ID 부분 일치")
            @RequestParam(required = false) String q,
            @Parameter(description = "페이지 번호 (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (최대 50)") @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminCameraService.getCameras(adminId, status, q, page, size));
    }

    @Operation(summary = "카메라 요약",
            description = """
                    디바이스 관리 상단 카드 숫자입니다. 모르는 값은 0이 아니라 null입니다.
                    - live·offline: AI 연결이 끊기면 null(aiConnected=false). 값이 있으면 live + offline = registered
                    - maxTotal: 서비스 전체 등록 상한. 상한이 꺼져 있으면 null
                    - totalWards: 이용 중인 피보호자 수, wardsWithoutCamera: 그중 카메라가 없는 수
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요약 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content)
    })
    @GetMapping("/summary")
    public ApiResponse<AdminCameraSummaryResponse> getSummary() {
        return ApiResponse.ok(adminCameraService.getSummary());
    }
}

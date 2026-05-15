package kr.silverbridge.main.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.admin.dto.AnomalyEventResponse;
import kr.silverbridge.main.domain.admin.service.AdminService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

@Tag(name = "관리자 - 이상감지 로그")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
public class AdminAnomalyEventController {

    private final AdminService adminService;

    @Operation(summary = "이상감지 이벤트 조회",
            description = """
                    AI 서버가 감지하여 저장된 이상감지 이벤트 이력을 조회합니다.

                    [이벤트 유형]
                    - FIRE: 화재 감지
                    - WEAPON: 흉기 감지
                    - FALL: 낙상 감지

                    [필터 조건] (모두 선택 사항)
                    - guardianId: 해당 보호자의 ACTIVE 연결 피보호자 이벤트만 조회. GUARDIAN 역할만 허용.
                    - startDate / endDate: 감지 일시(detectedAt) 범위. ISO 8601 형식 (예: 2025-01-01T00:00:00+09:00)

                    [피보호자 탈퇴 시]
                    wardName, wardEmail 은 null 로 반환됩니다.

                    [정렬]
                    - 감지 일시 내림차순
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "이상감지 이벤트 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "guardianId가 GUARDIAN 역할이 아님", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자 (guardianId 입력 시)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/event/abnormal")
    public ApiResponse<List<AnomalyEventResponse>> getAnomalyEvents(
            @Parameter(description = "보호자 ID (미입력 시 전체 조회, GUARDIAN 역할만 허용)")
            @RequestParam(required = false) String guardianId,
            @Parameter(description = "조회 시작 일시 (ISO 8601, 예: 2025-01-01T00:00:00+09:00)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startDate,
            @Parameter(description = "조회 종료 일시 (ISO 8601, 예: 2025-12-31T23:59:59+09:00)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endDate) {
        return ApiResponse.ok(adminService.getAnomalyEvents(guardianId, startDate, endDate));
    }
}

package kr.silverbridge.main.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.admin.dto.AdminAuditLogResponse;
import kr.silverbridge.main.domain.admin.service.AdminAuditLogService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "관리자 - 설정")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
public class AdminAuditLogController {

    private final AdminAuditLogService auditLogService;

    @Operation(summary = "관리자 행동 감사 로그 조회",
            description = """
                    관리자가 수행한 모든 행동 이력을 조회합니다.
                    사용자 상태/역할 변경, 강제 탈퇴, 연결 관리, 공지 CRUD 등 모든 변경 작업이 기록됩니다.

                    [기록되는 행동 유형]
                    - USER_STATUS_CHANGE: 사용자 상태 변경 (ACTIVE/INACTIVE)
                    - USER_ROLE_CHANGE: 사용자 역할 변경 + 연결 자동 해제
                    - USER_FORCE_DELETE: 사용자 강제 탈퇴
                    - FORCE_CONNECT: 보호자-피보호자 강제 연결
                    - FORCE_DISCONNECT: 보호자-피보호자 강제 연결 해제
                    - ANNOUNCEMENT_CREATE / UPDATE / DELETE: 공지 CRUD
                    - ANNOUNCEMENT_DRAFT_CREATE / UPDATE / DELETE / PUBLISH: 공지 임시저장

                    [정렬]
                    - 발생 일시 내림차순
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "감사 로그 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/audit/select")
    public ApiResponse<List<AdminAuditLogResponse>> getAuditLogs() {
        return ApiResponse.ok(auditLogService.getLogs());
    }
}
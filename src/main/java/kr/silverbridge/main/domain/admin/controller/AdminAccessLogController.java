package kr.silverbridge.main.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.admin.dto.AccessLogResponse;
import kr.silverbridge.main.domain.admin.service.AdminService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "관리자 - 회원관리")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
public class AdminAccessLogController {

    private final AdminService adminService;

    @Operation(summary = "사용자 접근 로그 조회",
            description = """
                    전체 접속 로그를 조회합니다.

                    [포함 이력]
                    - LOGIN: 일반 로그인
                    - KAKAO_LOGIN: 카카오 로그인
                    - LOGOUT: 로그아웃
                    - TOKEN_ISSUE: Access Token 재발급
                    - PASSWORD_RESET: 비밀번호 재설정

                    [정렬]
                    - 발생 일시 내림차순
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "접속 로그 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/accesslog/select")
    public ApiResponse<List<AccessLogResponse>> getAccessLogs() {
        return ApiResponse.ok(adminService.getAccessLogs());
    }
}
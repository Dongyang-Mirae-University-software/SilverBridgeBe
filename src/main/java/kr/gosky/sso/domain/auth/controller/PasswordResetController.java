package kr.gosky.sso.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.gosky.sso.domain.auth.dto.PasswordResetConfirmRequest;
import kr.gosky.sso.domain.auth.dto.PasswordResetRequest;
import kr.gosky.sso.domain.auth.service.PasswordResetService;
import kr.gosky.sso.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "비밀번호 재설정", description = "비밀번호 찾기 및 재설정 API")
@RestController
@RequestMapping("/api/auth/password")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    @Operation(summary = "비밀번호 재설정 요청", description = "입력한 이메일로 비밀번호 재설정 토큰을 발송합니다. 토큰의 유효 시간은 30분입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재설정 이메일 발송 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 유효성 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 이메일")
    })
    @PostMapping("/reset-request")
    public ApiResponse<Void> requestReset(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.requestReset(request);
        return ApiResponse.ok("비밀번호 재설정 이메일이 발송되었습니다.");
    }

    @Operation(summary = "비밀번호 재설정 확인", description = "재설정 토큰을 검증하고 새 비밀번호로 변경합니다. 변경 후 모든 기기에서 자동 로그아웃됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "비밀번호 변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "유효하지 않거나 만료된 토큰, 또는 입력값 유효성 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음")
    })
    @PostMapping("/reset")
    public ApiResponse<Void> confirmReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.confirmReset(request);
        return ApiResponse.ok("비밀번호가 변경되었습니다.");
    }
}

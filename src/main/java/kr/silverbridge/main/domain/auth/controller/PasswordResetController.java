package kr.silverbridge.main.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.auth.dto.PasswordResetConfirmRequest;
import kr.silverbridge.main.domain.auth.service.PasswordResetService;
import kr.silverbridge.main.global.response.ApiResponse;
import kr.silverbridge.main.global.security.RateLimitService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증")
@RestController
@RequestMapping("/api/auth/password")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;
    private final RateLimitService rateLimitService;

    @Operation(
            summary = "[공통] 3단계 · 새 비밀번호 설정",
            description = """
                    이메일 또는 SMS 방식으로 발급된 token과 새 비밀번호를 입력하여 비밀번호를 변경합니다.
                    변경 성공 시 모든 기기에서 자동 로그아웃됩니다. (재로그인 필요)

                    [token 출처]
                    - 이메일 방식: POST /api/auth/find-password/email/verify 응답의 token 값
                    - SMS 방식: POST /api/auth/find-password/sms/verify 응답의 token 값

                    [비밀번호 조건]
                    - 숫자·특수문자 포함, 공백 없이 8자 이상
                    - 현재 비밀번호와 동일 불가
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "비밀번호 변경 성공. 모든 기기에서 로그아웃됨 → 재로그인 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "token 만료 또는 유효하지 않음 / 현재 비밀번호와 동일 / 비밀번호 형식 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류", content = @Content)
    })
    @PostMapping("/reset")
    public ApiResponse<Void> confirmReset(@Valid @RequestBody PasswordResetConfirmRequest request,
                                          HttpServletRequest httpRequest) {
        rateLimitService.check("pw-reset-confirm", httpRequest.getRemoteAddr());
        passwordResetService.confirmReset(request);
        return ApiResponse.ok("비밀번호가 변경되었습니다.");
    }
}

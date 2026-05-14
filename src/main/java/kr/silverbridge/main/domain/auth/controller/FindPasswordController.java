package kr.silverbridge.main.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.auth.dto.PasswordResetEmailVerifyRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetSmsSendRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetSmsVerifyRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetTokenResponse;
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
@RequestMapping("/api/auth/find-password")
@RequiredArgsConstructor
public class FindPasswordController {

    private final PasswordResetService passwordResetService;
    private final RateLimitService rateLimitService;

    // ─── 이메일 방식 ───────────────────────────────────────────

    @Operation(
            summary = "[이메일] 1단계 · 인증코드 발송",
            description = """
                    가입된 이메일로 6자리 인증코드를 발송합니다.

                    [이메일 방식 전체 흐름]
                    1. POST /api/auth/find-password/email/send   → 인증코드 이메일 발송
                    2. POST /api/auth/find-password/email/verify → 인증코드 확인 → token 반환
                    3. POST /api/auth/password/reset             → token + 새 비밀번호로 변경

                    [주의사항]
                    - 보안상 이유로 해당 이메일이 존재하지 않아도 200을 반환합니다. (이메일 존재 여부 노출 방지)
                    - 카카오로 가입한 계정은 이메일이 있어도 발송되지 않습니다.
                    - 인증코드 유효 시간: 5분 / 재발송 가능 시간: 1분 후
                    - 5회 이상 오류 시 인증코드 초기화 → 재발송 필요
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요청 처리 완료 (이메일 미존재 또는 카카오 계정이어도 동일하게 200 반환)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "이메일 형식 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "1분 이내 재발송 불가", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "이메일 발송 실패", content = @Content)
    })
    @PostMapping("/email/send")
    public ApiResponse<Void> sendEmail(@Valid @RequestBody PasswordResetRequest request,
                                       HttpServletRequest httpRequest) {
        rateLimitService.check("pw-reset-email", httpRequest.getRemoteAddr());
        passwordResetService.requestReset(request);
        return ApiResponse.ok("비밀번호 재설정 인증코드가 발송되었습니다.");
    }

    @Operation(
            summary = "[이메일] 2단계 · 인증코드 확인 및 token 발급",
            description = """
                    이메일로 받은 6자리 인증코드를 확인합니다.
                    인증 성공 시 비밀번호 변경에 필요한 token이 반환됩니다.
                    반환된 token을 POST /api/auth/password/reset 의 token 필드에 전달하세요.

                    [제한사항]
                    - token 유효 시간: 30분
                    - 5회 이상 오류 시 인증코드 초기화 → 인증코드 재발송 필요
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "인증 성공. 응답의 token 값을 POST /api/auth/password/reset 에 전달하세요."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "인증코드 불일치 / 인증코드 만료 / 5회 이상 오류로 인증코드 초기화됨", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음", content = @Content)
    })
    @PostMapping("/email/verify")
    public ApiResponse<PasswordResetTokenResponse> verifyEmail(@Valid @RequestBody PasswordResetEmailVerifyRequest request) {
        return ApiResponse.ok(passwordResetService.verifyEmailToken(request));
    }

    @Operation(
            summary = "[이메일] 재발송",
            description = """
                    비밀번호 재설정 이메일 인증코드를 재발송합니다.
                    기존 인증코드는 즉시 무효화되고 새 코드가 발송됩니다.

                    [제한사항]
                    - 재발송 가능 시간: 1분 후
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요청 처리 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "이메일 형식 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "1분 이내 재발송 불가", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "이메일 발송 실패", content = @Content)
    })
    @PostMapping("/email/resend")
    public ApiResponse<Void> resendEmail(@Valid @RequestBody PasswordResetRequest request,
                                         HttpServletRequest httpRequest) {
        rateLimitService.check("pw-reset-email", httpRequest.getRemoteAddr());
        passwordResetService.requestReset(request);
        return ApiResponse.ok("비밀번호 재설정 인증코드가 재발송되었습니다.");
    }

    // ─── SMS 방식 ────────────────────────────────────────────

    @Operation(
            summary = "[SMS] 1단계 · 인증코드 발송",
            description = """
                    이름과 전화번호로 가입 여부를 확인 후 인증코드를 SMS로 발송합니다.

                    [SMS 방식 전체 흐름]
                    1. POST /api/auth/find-password/sms/send   → 인증코드 SMS 발송
                    2. POST /api/auth/find-password/sms/verify → 인증코드 확인 → token 반환
                    3. POST /api/auth/password/reset           → token + 새 비밀번호로 변경

                    [주의사항]
                    - 보안상 이유로 일치하는 계정이 없어도 200을 반환합니다. (가입 여부 노출 방지)
                    - 카카오로 가입한 계정은 발송되지 않습니다.
                    - 인증코드 유효 시간: 5분 / 재발송 가능 시간: 1분 후
                    - 5회 이상 오류 시 인증코드 초기화 → 재발송 필요
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요청 처리 완료 (일치하는 계정 없거나 카카오 계정이어도 동일하게 200 반환)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "1분 이내 재발송 불가", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "SMS 발송 실패", content = @Content)
    })
    @PostMapping("/sms/send")
    public ApiResponse<Void> sendSms(@Valid @RequestBody PasswordResetSmsSendRequest request,
                                     HttpServletRequest httpRequest) {
        rateLimitService.check("pw-reset-sms", httpRequest.getRemoteAddr());
        passwordResetService.requestResetBySms(request);
        return ApiResponse.ok("비밀번호 재설정 인증코드가 발송되었습니다.");
    }

    @Operation(
            summary = "[SMS] 2단계 · 인증코드 확인 및 token 발급",
            description = """
                    SMS로 받은 인증코드를 확인합니다.
                    인증 성공 시 비밀번호 변경에 필요한 token이 반환됩니다.
                    반환된 token을 POST /api/auth/password/reset 의 token 필드에 전달하세요.

                    [제한사항]
                    - token 유효 시간: 30분
                    - 5회 이상 오류 시 인증코드 초기화 → 인증코드 재발송 필요
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "인증 성공. 응답의 token 값을 POST /api/auth/password/reset 에 전달하세요."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "인증코드 불일치 / 인증코드 만료 / 5회 이상 오류로 인증코드 초기화됨", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음", content = @Content)
    })
    @PostMapping("/sms/verify")
    public ApiResponse<PasswordResetTokenResponse> verifySms(@Valid @RequestBody PasswordResetSmsVerifyRequest request) {
        return ApiResponse.ok(passwordResetService.verifySmsAndIssueToken(request));
    }

    @Operation(
            summary = "[SMS] 재발송",
            description = """
                    비밀번호 재설정 SMS 인증코드를 재발송합니다.
                    기존 인증코드는 즉시 무효화되고 새 코드가 발송됩니다.

                    [제한사항]
                    - 재발송 가능 시간: 1분 후
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요청 처리 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "1분 이내 재발송 불가", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "SMS 발송 실패", content = @Content)
    })
    @PostMapping("/sms/resend")
    public ApiResponse<Void> resendSms(@Valid @RequestBody PasswordResetSmsSendRequest request,
                                       HttpServletRequest httpRequest) {
        rateLimitService.check("pw-reset-sms", httpRequest.getRemoteAddr());
        passwordResetService.requestResetBySms(request);
        return ApiResponse.ok("비밀번호 재설정 인증코드가 재발송되었습니다.");
    }
}

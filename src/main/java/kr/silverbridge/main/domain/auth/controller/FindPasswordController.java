package kr.silverbridge.main.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.auth.dto.CodeSentResponse;
import kr.silverbridge.main.domain.auth.dto.PasswordResetEmailVerifyRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetSmsSendRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetSmsVerifyRequest;
import kr.silverbridge.main.domain.auth.service.PasswordResetService;
import kr.silverbridge.main.domain.auth.service.SmsVerificationService;
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

    /** 인증코드 발송/재발송 응답 (프론트 카운트다운용). 코드 = 숫자 6자리. */
    private CodeSentResponse codeSent() {
        return new CodeSentResponse((int) SmsVerificationService.CODE_TTL_SECONDS, 6);
    }

    // ─── 이메일 방식 ───────────────────────────────────────────

    @Operation(
            summary = "[이메일] 1단계 · 인증코드 발송",
            description = """
                    가입된 이메일로 6자리 숫자 인증코드를 발송합니다.

                    [응답 data]
                    - expiresInSeconds: 코드 유효 시간(초). 화면 카운트다운 시작값 (현재 300초 = 5분)
                    - codeLength: 코드 자릿수 (항상 6)

                    [이메일 방식 전체 흐름] — UUID 토큰 없음, 6자리 코드 하나로 통일
                    1. POST /api/auth/find-password/email/send   → 인증코드 이메일 발송 (이 API)
                    2. POST /api/auth/find-password/email/verify → 6자리 코드 확인 (pre-check, 코드 미소비)
                    3. POST /api/auth/password/reset             → 같은 email + 같은 6자리 code + 새 비밀번호

                    [보안·동작]
                    - 이메일이 존재하지 않거나 카카오 가입 계정이어도 동일하게 200을 반환합니다.
                      (가입 여부 노출 방지 — 프론트는 항상 "발송됨"으로 처리)
                    - 재발송 쿨다운 없음: 잘못 눌러도 즉시 다시 호출 가능 (기존 코드는 새 코드로 교체)
                    - 코드를 5회 잘못 입력하면 코드가 무효화됩니다. → 재발송 필요
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요청 처리 완료 (미존재/카카오 계정이어도 동일하게 200). data.expiresInSeconds로 카운트다운"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "이메일 형식 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "동일 IP의 과도한 요청 (IP RateLimit)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "이메일 발송 실패", content = @Content)
    })
    @PostMapping("/email/send")
    public ApiResponse<CodeSentResponse> sendEmail(@Valid @RequestBody PasswordResetRequest request,
                                                   HttpServletRequest httpRequest) {
        rateLimitService.check("pw-reset-email", httpRequest.getRemoteAddr());
        passwordResetService.requestReset(request);
        return ApiResponse.ok(codeSent());
    }

    @Operation(
            summary = "[이메일] 2단계 · 인증코드 확인 (pre-check)",
            description = """
                    이메일로 받은 6자리 숫자 인증코드가 맞는지 확인합니다. (Image 5 "확인" 버튼)

                    [중요] 토큰을 발급하지 않습니다. 이 단계는 코드를 소비하지 않는 사전 확인이며,
                    성공하면 다음 화면(새 비밀번호)으로 진행한 뒤
                    POST /api/auth/password/reset 에 **같은 email + 같은 6자리 code + 새 비밀번호**를 전달하세요.

                    [제한사항]
                    - 코드 유효 시간: 5분
                    - 코드 5회 오류 시 코드 무효화 → 코드 재발송 필요
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "코드 확인 성공. 같은 email+code로 POST /api/auth/password/reset 진행"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "코드 형식 오류(숫자 6자리 아님) / 코드 불일치 / 코드 만료 / 5회 초과로 무효화됨", content = @Content)
    })
    @PostMapping("/email/verify")
    public ApiResponse<Void> verifyEmail(@Valid @RequestBody PasswordResetEmailVerifyRequest request) {
        passwordResetService.verifyEmailCode(request);
        return ApiResponse.ok("인증되었습니다. 새 비밀번호를 설정해주세요.");
    }

    @Operation(
            summary = "[이메일] 재발송",
            description = """
                    비밀번호 재설정 이메일 인증코드를 재발송합니다.
                    동작은 1단계 발송과 동일하며 기존 코드는 새 코드로 교체됩니다.
                    재발송 쿨다운 없음(즉시 재요청 가능).

                    [응답 data] expiresInSeconds(초), codeLength(6)
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요청 처리 완료. data.expiresInSeconds로 카운트다운 재시작"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "이메일 형식 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "동일 IP의 과도한 요청 (IP RateLimit)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "이메일 발송 실패", content = @Content)
    })
    @PostMapping("/email/resend")
    public ApiResponse<CodeSentResponse> resendEmail(@Valid @RequestBody PasswordResetRequest request,
                                                     HttpServletRequest httpRequest) {
        rateLimitService.check("pw-reset-email", httpRequest.getRemoteAddr());
        passwordResetService.requestReset(request);
        return ApiResponse.ok(codeSent());
    }

    // ─── SMS 방식 ────────────────────────────────────────────

    @Operation(
            summary = "[SMS] 1단계 · 인증코드 발송",
            description = """
                    이름과 전화번호로 가입 여부를 확인한 뒤 6자리 숫자 인증코드를 SMS로 발송합니다.

                    [응답 data]
                    - expiresInSeconds: 코드 유효 시간(초). 화면 카운트다운 시작값 (현재 300초 = 5분)
                    - codeLength: 코드 자릿수 (항상 6)

                    [SMS 방식 전체 흐름] — UUID 토큰 없음, 6자리 코드 하나로 통일
                    1. POST /api/auth/find-password/sms/send   → 인증코드 SMS 발송 (이 API)
                    2. POST /api/auth/find-password/sms/verify → 6자리 코드 확인 (pre-check, 코드 미소비)
                    3. POST /api/auth/password/reset           → 같은 phone + 같은 6자리 code + 새 비밀번호

                    [보안·동작]
                    - 일치하는 계정이 없거나 카카오 가입 계정이어도 동일하게 200을 반환합니다. (가입 여부 노출 방지)
                    - 재발송 쿨다운 없음: 잘못 눌러도 즉시 다시 호출 가능
                    - 코드 5회 오류 시 코드 무효화 → 재발송 필요
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요청 처리 완료 (미일치/카카오 계정이어도 동일하게 200). data.expiresInSeconds로 카운트다운"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 오류 (이름 누락, 전화번호 형식 오류 등)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "동일 IP의 과도한 요청 (IP RateLimit)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "SMS 발송 실패", content = @Content)
    })
    @PostMapping("/sms/send")
    public ApiResponse<CodeSentResponse> sendSms(@Valid @RequestBody PasswordResetSmsSendRequest request,
                                                 HttpServletRequest httpRequest) {
        rateLimitService.check("pw-reset-sms", httpRequest.getRemoteAddr());
        passwordResetService.requestResetBySms(request);
        return ApiResponse.ok(codeSent());
    }

    @Operation(
            summary = "[SMS] 2단계 · 인증코드 확인 (pre-check)",
            description = """
                    SMS로 받은 6자리 숫자 인증코드가 맞는지 확인합니다. (Image 5 "확인" 버튼)

                    [중요] 토큰을 발급하지 않습니다. 코드를 소비하지 않는 사전 확인이며,
                    성공하면 다음 화면으로 진행한 뒤
                    POST /api/auth/password/reset 에 **같은 phone + 같은 6자리 code + 새 비밀번호**를 전달하세요.

                    [제한사항]
                    - 코드 유효 시간: 5분
                    - 코드 5회 오류 시 코드 무효화 → 재발송 필요
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "코드 확인 성공. 같은 phone+code로 POST /api/auth/password/reset 진행"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "코드 형식 오류(숫자 6자리 아님) / 코드 불일치 / 코드 만료 / 5회 초과로 무효화됨", content = @Content)
    })
    @PostMapping("/sms/verify")
    public ApiResponse<Void> verifySms(@Valid @RequestBody PasswordResetSmsVerifyRequest request) {
        passwordResetService.verifySmsCode(request);
        return ApiResponse.ok("인증되었습니다. 새 비밀번호를 설정해주세요.");
    }

    @Operation(
            summary = "[SMS] 재발송",
            description = """
                    비밀번호 재설정 SMS 인증코드를 재발송합니다.
                    동작은 1단계 발송과 동일하며 기존 코드는 새 코드로 교체됩니다.
                    재발송 쿨다운 없음(즉시 재요청 가능).

                    [응답 data] expiresInSeconds(초), codeLength(6)
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요청 처리 완료. data.expiresInSeconds로 카운트다운 재시작"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "동일 IP의 과도한 요청 (IP RateLimit)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "SMS 발송 실패", content = @Content)
    })
    @PostMapping("/sms/resend")
    public ApiResponse<CodeSentResponse> resendSms(@Valid @RequestBody PasswordResetSmsSendRequest request,
                                                   HttpServletRequest httpRequest) {
        rateLimitService.check("pw-reset-sms", httpRequest.getRemoteAddr());
        passwordResetService.requestResetBySms(request);
        return ApiResponse.ok(codeSent());
    }
}

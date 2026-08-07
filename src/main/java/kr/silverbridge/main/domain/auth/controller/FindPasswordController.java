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
import kr.silverbridge.main.global.util.ClientIpResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "공통 - 인증")
@RestController
@RequestMapping("/api/auth/find-password")
@RequiredArgsConstructor
public class FindPasswordController {

    private final PasswordResetService passwordResetService;
    private final RateLimitService rateLimitService;

    // 비밀번호 재설정 발송/재발송 IP 속도 제한 (2026-05-23) — 미가입 응답이 노출되므로 분+시간 이중 윈도우로
    // 자동화 enumeration·어뷰징 방어. 1분 한도는 시니어의 반복 재발송을 고려해 여유(10회), 시간 한도(30회)가
    // 분산 저빈도 스윕을 차단. send·resend는 같은 endpoint 키를 공유한다(기능 동일).
    private static final int PW_RESET_MAX_PER_MINUTE = 10;
    private static final int PW_RESET_MAX_PER_HOUR   = 30;

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
                    2. POST /api/auth/find-password/email/verify → 6자리 코드 사전 확인 (코드 소비 안 함)
                    3. POST /api/auth/password/reset             → 같은 email + 같은 6자리 code + 새 비밀번호

                    [보안·동작] (2026-05-23 정책 변경 — 시니어/4050 타겟 UX 우선, always-200 폐지)
                    - 미가입 이메일: 404 "해당 이메일로 가입된 계정이 없습니다"
                    - 카카오로 가입한 계정: 400 "카카오로 가입한 계정은 비밀번호 재설정을 사용할 수 없습니다"
                    - enumeration·어뷰징 방어: IP 이중 윈도우 RateLimit(1분 10회/1시간 30회) + per-email 발송 상한(1시간 10회)
                    - 재발송 쿨다운 없음: 잘못 눌러도 즉시 다시 호출 가능 (기존 코드는 새 코드로 교체)
                    - 코드를 5회 잘못 입력하면 코드가 무효화됩니다. → 재발송 필요
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "인증코드 발송 완료. data.expiresInSeconds로 카운트다운"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "이메일 형식 오류 또는 카카오로 가입한 계정", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "해당 이메일로 가입된 계정 없음 (2026-05-23 정책 변경)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "동일 IP 과도한 요청(1분 10회/1시간 30회) 또는 per-email 발송 상한 초과", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "이메일 발송 실패", content = @Content)
    })
    @PostMapping("/email/send")
    public ApiResponse<CodeSentResponse> sendEmail(@Valid @RequestBody PasswordResetRequest request,
                                                   HttpServletRequest httpRequest) {
        String ip = ClientIpResolver.resolve(httpRequest);
        rateLimitService.check("pw-reset-email", ip, PW_RESET_MAX_PER_MINUTE, PW_RESET_MAX_PER_HOUR);
        passwordResetService.requestReset(request, ip);
        return ApiResponse.ok(codeSent());
    }

    @Operation(
            summary = "[이메일] 2단계 · 인증코드 사전 확인",
            description = """
                    인증코드 입력 화면에서 사용자가 '확인'을 눌렀을 때 호출합니다.
                    이메일로 받은 6자리 숫자 인증코드가 맞는지 검사만 합니다.

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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "인증코드 재발송 완료. data.expiresInSeconds로 카운트다운 재시작"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "이메일 형식 오류 또는 카카오로 가입한 계정", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "해당 이메일로 가입된 계정 없음 (2026-05-23 정책 변경)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "동일 IP 과도한 요청(1분 10회/1시간 30회) 또는 per-email 발송 상한 초과", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "이메일 발송 실패", content = @Content)
    })
    @PostMapping("/email/resend")
    public ApiResponse<CodeSentResponse> resendEmail(@Valid @RequestBody PasswordResetRequest request,
                                                     HttpServletRequest httpRequest) {
        String ip = ClientIpResolver.resolve(httpRequest);
        rateLimitService.check("pw-reset-email", ip, PW_RESET_MAX_PER_MINUTE, PW_RESET_MAX_PER_HOUR);
        passwordResetService.requestReset(request, ip);
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
                    2. POST /api/auth/find-password/sms/verify → 6자리 코드 사전 확인 (코드 소비 안 함)
                    3. POST /api/auth/password/reset           → 같은 phone + 같은 6자리 code + 새 비밀번호

                    [보안·동작] (2026-05-23 정책 변경 — 시니어/4050 타겟 UX 우선, always-200 폐지)
                    - 이름+전화번호 미일치: 404 "사용자를 찾을 수 없습니다"
                    - 카카오로 가입한 계정만 매칭: 400 "카카오로 가입한 계정은 비밀번호 재설정을 사용할 수 없습니다"
                    - SMS 비용 보호: 미가입자에게는 SMS를 발송하지 않습니다(404 선차단). 특정 번호 SMS 폭탄은 per-phone 발송 상한(1시간 10회)이 차단
                    - enumeration·어뷰징 방어: IP 이중 윈도우 RateLimit(1분 10회/1시간 30회)
                    - 재발송 쿨다운 없음: 잘못 눌러도 즉시 다시 호출 가능
                    - 코드 5회 오류 시 코드 무효화 → 재발송 필요
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "인증코드 발송 완료. data.expiresInSeconds로 카운트다운"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 오류(이름 누락, 전화번호 형식 오류) 또는 카카오로 가입한 계정", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없습니다 (이름+전화번호 미일치, 2026-05-23 정책 변경)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "동일 IP 과도한 요청(1분 10회/1시간 30회) 또는 per-phone 발송 상한 초과", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "SMS 발송 실패", content = @Content)
    })
    @PostMapping("/sms/send")
    public ApiResponse<CodeSentResponse> sendSms(@Valid @RequestBody PasswordResetSmsSendRequest request,
                                                 HttpServletRequest httpRequest) {
        String ip = ClientIpResolver.resolve(httpRequest);
        rateLimitService.check("pw-reset-sms", ip, PW_RESET_MAX_PER_MINUTE, PW_RESET_MAX_PER_HOUR);
        passwordResetService.requestResetBySms(request, ip);
        return ApiResponse.ok(codeSent());
    }

    @Operation(
            summary = "[SMS] 2단계 · 인증코드 사전 확인",
            description = """
                    인증코드 입력 화면에서 사용자가 '확인'을 눌렀을 때 호출합니다.
                    SMS로 받은 6자리 숫자 인증코드가 맞는지 검사만 합니다.

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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "인증코드 재발송 완료. data.expiresInSeconds로 카운트다운 재시작"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 오류 또는 카카오로 가입한 계정", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없습니다 (이름+전화번호 미일치, 2026-05-23 정책 변경)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "동일 IP 과도한 요청(1분 10회/1시간 30회) 또는 per-phone 발송 상한 초과", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "SMS 발송 실패", content = @Content)
    })
    @PostMapping("/sms/resend")
    public ApiResponse<CodeSentResponse> resendSms(@Valid @RequestBody PasswordResetSmsSendRequest request,
                                                   HttpServletRequest httpRequest) {
        String ip = ClientIpResolver.resolve(httpRequest);
        rateLimitService.check("pw-reset-sms", ip, PW_RESET_MAX_PER_MINUTE, PW_RESET_MAX_PER_HOUR);
        passwordResetService.requestResetBySms(request, ip);
        return ApiResponse.ok(codeSent());
    }
}

package kr.silverbridge.main.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.auth.dto.CodeSentResponse;
import kr.silverbridge.main.domain.auth.dto.SmsSendRequest;
import kr.silverbridge.main.domain.auth.dto.SmsVerifyRequest;
import kr.silverbridge.main.domain.auth.dto.SmsVerifyResponse;
import kr.silverbridge.main.domain.auth.service.SmsService;
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
@RequestMapping("/api/auth/signup/sms")
@RequiredArgsConstructor
public class SmsController {

    private final SmsService smsService;
    private final RateLimitService rateLimitService;

    /** 인증코드 발송/재발송 응답에 내려줄 값 (프론트 카운트다운용). 코드 = 숫자 6자리. */
    private CodeSentResponse codeSent() {
        return new CodeSentResponse((int) SmsVerificationService.CODE_TTL_SECONDS, 6);
    }

    @Operation(
            summary = "SMS 인증코드 발송 (회원가입·전화번호 변경 공통)",
            description = """
                    입력한 전화번호로 6자리 숫자 인증코드를 SMS로 발송합니다.

                    [응답 data]
                    - expiresInSeconds: 인증코드 유효 시간(초). 이 값으로 화면 카운트다운을 시작하세요. (현재 300초 = 5분)
                    - codeLength: 입력해야 할 코드 자릿수 (항상 6)

                    [동작]
                    - 이미 가입된 전화번호이면 SMS 발송 없이 409를 반환합니다.
                      (회원가입 흐름에서 전화번호 중복은 이 단계에서 먼저 걸러집니다.)
                    - 재발송 쿨다운이 없습니다. 잘못 눌렀거나 SMS를 못 받았으면 즉시 다시 호출해도 됩니다.
                      (호출 시마다 기존 코드는 폐기되고 새 코드로 교체됩니다.)
                    - 재발송 전용 엔드포인트 POST /api/auth/signup/sms/resend 도 동작은 동일합니다.

                    [회원가입 SMS 인증 흐름]
                    1. POST /api/auth/signup/sms/send   → 코드 발송 (이 API)
                    2. POST /api/auth/signup/sms/verify → 코드 확인 → verificationNonce 수령
                    3. POST /api/auth/signup            → verificationNonce 동봉하여 가입 완료
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "SMS 발송 성공. data.expiresInSeconds로 카운트다운 시작"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "전화번호 형식 오류 (숫자 10~11자리만 허용)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 가입된 전화번호", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "동일 IP의 과도한 요청 (IP RateLimit). 잠시 후 재시도", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "SMS 발송 실패 (통신사 오류)", content = @Content)
    })
    @PostMapping("/send")
    public ApiResponse<CodeSentResponse> send(@Valid @RequestBody SmsSendRequest request,
                                              HttpServletRequest httpRequest) {
        rateLimitService.check("signup-sms", ClientIpResolver.resolve(httpRequest));
        smsService.sendVerificationCode(request);
        return ApiResponse.ok(codeSent());
    }

    @Operation(
            summary = "SMS 인증코드 확인",
            description = """
                    발송된 6자리 숫자 인증코드를 확인하여 전화번호 소유를 검증합니다.
                    인증 성공 시 verificationNonce(UUID)가 반환됩니다.

                    [verificationNonce 사용처]
                    회원가입(POST /api/auth/signup, POST /api/auth/signup/kakao) 또는
                    전화번호 변경(PUT /api/user/me) 요청의 verificationNonce 필드에 이 값을 그대로 전달하세요.

                    [제한사항]
                    - 인증 완료 후 10분 이내에 후속 API를 호출해야 nonce가 유효합니다.
                    - 코드를 5회 잘못 입력하면 코드가 무효화됩니다. → 코드 재발송 필요
                    - 코드 유효 시간(5분) 초과 시 만료 오류가 반환됩니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "인증 성공. data.verificationNonce를 회원가입/전화번호 변경 요청에 전달"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "코드 형식 오류(숫자 6자리 아님) / 코드 불일치 / 코드 만료 / 5회 초과로 코드 무효화됨", content = @Content)
    })
    @PostMapping("/verify")
    public ApiResponse<SmsVerifyResponse> verify(@Valid @RequestBody SmsVerifyRequest request) {
        String nonce = smsService.verifyCode(request);
        return ApiResponse.ok(new SmsVerifyResponse(nonce));
    }

    @Operation(
            summary = "SMS 인증코드 재발송",
            description = """
                    인증코드를 재발송합니다. 동작은 POST /api/auth/signup/sms/send 와 동일하며,
                    기존 코드는 폐기되고 새 코드로 교체됩니다.
                    재발송 쿨다운은 없습니다(즉시 재요청 가능).

                    [응답 data] expiresInSeconds(초), codeLength(6)
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "SMS 재발송 성공. data.expiresInSeconds로 카운트다운 재시작"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "전화번호 형식 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 가입된 전화번호", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "동일 IP의 과도한 요청 (IP RateLimit)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "SMS 발송 실패", content = @Content)
    })
    @PostMapping("/resend")
    public ApiResponse<CodeSentResponse> resend(@Valid @RequestBody SmsSendRequest request,
                                                HttpServletRequest httpRequest) {
        rateLimitService.check("signup-sms", ClientIpResolver.resolve(httpRequest));
        smsService.sendVerificationCode(request);
        return ApiResponse.ok(codeSent());
    }
}

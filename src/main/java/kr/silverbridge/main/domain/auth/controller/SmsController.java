package kr.silverbridge.main.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.auth.dto.SmsSendRequest;
import kr.silverbridge.main.domain.auth.dto.SmsVerifyRequest;
import kr.silverbridge.main.domain.auth.service.SmsService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증")
@RestController
@RequestMapping("/api/auth/sms")
@RequiredArgsConstructor
public class SmsController {

    private final SmsService smsService;

    @Operation(
            summary = "SMS 인증코드 발송",
            description = """
                    입력한 전화번호로 6자리 인증코드를 발송합니다.
                    회원가입 및 전화번호 변경 시 공통으로 사용합니다.

                    이미 가입된 전화번호이면 SMS 발송 전에 409를 반환합니다.
                    → 회원가입 흐름에서 전화번호 중복은 이 단계에서 먼저 확인됩니다.

                    [제한사항]
                    - 인증코드 유효 시간: 5분
                    - 재발송 가능 시간: 1분 후
                    - 재발송 시 기존 인증코드는 즉시 무효화됩니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "SMS 발송 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "전화번호 형식 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 가입된 전화번호"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "1분 이내 재발송 불가 (재발송 제한)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "SMS 발송 실패 (통신사 오류)")
    })
    @PostMapping("/send")
    public ApiResponse<Void> send(@Valid @RequestBody SmsSendRequest request) {
        smsService.sendVerificationCode(request);
        return ApiResponse.ok("인증코드가 발송되었습니다.");
    }

    @Operation(
            summary = "SMS 인증코드 확인",
            description = """
                    발송된 인증코드를 입력하여 전화번호를 인증합니다.
                    인증 완료 후 10분 이내에 회원가입(POST /api/auth/register 또는 POST /api/auth/kakao/register)을 진행해야 합니다.

                    [제한사항]
                    - 5회 이상 오류 시 인증코드가 초기화됩니다. → 인증코드 재발송 필요
                    - 인증코드 유효 시간(5분) 초과 시 만료 오류가 반환됩니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "인증 성공. 10분 이내에 회원가입 API를 호출하세요."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "인증코드 불일치 / 인증코드 만료 / 5회 이상 오류로 인증코드 초기화됨")
    })
    @PostMapping("/verify")
    public ApiResponse<Void> verify(@Valid @RequestBody SmsVerifyRequest request) {
        smsService.verifyCode(request);
        return ApiResponse.ok("SMS 인증이 완료되었습니다.");
    }
}

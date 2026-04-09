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

@Tag(name = "SMS 인증", description = "SMS 인증 코드 발송 및 검증 API (회원가입 전화번호 인증)")
@RestController
@RequestMapping("/api/auth/sms")
@RequiredArgsConstructor
public class SmsController {

    private final SmsService smsService;

    @Operation(summary = "SMS 인증 코드 발송", description = "입력한 전화번호로 6자리 인증 코드를 발송합니다. 유효 시간은 5분이며, 재발송 시 기존 코드는 즉시 무효화됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "SMS 발송 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "전화번호 형식 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "SMS 발송 실패")
    })
    @PostMapping("/send")
    public ApiResponse<Void> send(@Valid @RequestBody SmsSendRequest request) {
        smsService.sendVerificationCode(request);
        return ApiResponse.ok("인증 코드가 발송되었습니다.");
    }

    @Operation(summary = "SMS 인증 코드 검증", description = "발송된 인증 코드를 검증합니다. 코드가 일치하면 10분간 회원가입 가능 상태가 됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "SMS 인증 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "인증코드 불일치 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PostMapping("/verify")
    public ApiResponse<Void> verify(@Valid @RequestBody SmsVerifyRequest request) {
        smsService.verifyCode(request);
        return ApiResponse.ok("SMS 인증이 완료되었습니다.");
    }
}

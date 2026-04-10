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

@Tag(name = "SMS 인증", description = "회원가입 시 전화번호 인증 API")
@RestController
@RequestMapping("/api/auth/sms")
@RequiredArgsConstructor
public class SmsController {

    private final SmsService smsService;

    @Operation(summary = "SMS 인증코드 발송", description = "입력한 전화번호로 6자리 인증코드를 보냅니다. 인증코드는 5분간 유효하며, 재발송은 1분 후 가능합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "SMS 발송 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "전화번호 형식 오류 또는 재발송 제한 중"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "SMS 발송 실패")
    })
    @PostMapping("/send")
    public ApiResponse<Void> send(@Valid @RequestBody SmsSendRequest request) {
        smsService.sendVerificationCode(request);
        return ApiResponse.ok("인증코드가 발송되었습니다.");
    }

    @Operation(summary = "SMS 인증코드 확인", description = "발송된 인증코드를 입력하여 전화번호를 인증합니다. 인증 완료 후 10분 이내에 회원가입을 진행해야 합니다. 5회 이상 틀리면 인증코드가 초기화됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "인증 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "인증코드 불일치, 만료, 또는 5회 초과")
    })
    @PostMapping("/verify")
    public ApiResponse<Void> verify(@Valid @RequestBody SmsVerifyRequest request) {
        smsService.verifyCode(request);
        return ApiResponse.ok("SMS 인증이 완료되었습니다.");
    }
}

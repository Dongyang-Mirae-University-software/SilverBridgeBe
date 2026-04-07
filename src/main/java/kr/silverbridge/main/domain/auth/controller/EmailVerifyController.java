package kr.silverbridge.main.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.auth.dto.EmailSendRequest;
import kr.silverbridge.main.domain.auth.dto.EmailVerifyRequest;
import kr.silverbridge.main.domain.auth.service.EmailVerifyService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "이메일 인증", description = "이메일 인증 코드 발송 및 검증 API")
@RestController
@RequestMapping("/api/auth/email")
@RequiredArgsConstructor
public class EmailVerifyController {

    private final EmailVerifyService emailVerifyService;

    @Operation(summary = "인증 코드 발송 / 재발송", description = "입력한 이메일로 6자리 인증 코드를 발송합니다. 코드 유효 시간은 5분이며, 같은 API를 다시 호출하면 재발송됩니다. 재발송 시 기존 코드는 즉시 무효화됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "인증 코드 발송 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 유효성 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 이메일"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 인증 완료된 이메일"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PostMapping("/send")
    public ApiResponse<Void> send(@Valid @RequestBody EmailSendRequest request) {
        emailVerifyService.sendVerificationCode(request);
        return ApiResponse.ok("인증 코드가 발송되었습니다.");
    }

    @Operation(summary = "인증 코드 검증", description = "발송된 인증 코드를 검증합니다. 코드가 일치하면 이메일 인증 완료 처리됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "이메일 인증 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "인증 코드 불일치 또는 만료된 코드"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 이메일"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PostMapping("/verify")
    public ApiResponse<Void> verify(@Valid @RequestBody EmailVerifyRequest request) {
        emailVerifyService.verifyCode(request);
        return ApiResponse.ok("이메일 인증이 완료되었습니다.");
    }
}

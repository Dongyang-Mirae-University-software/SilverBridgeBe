package kr.silverbridge.main.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.auth.dto.PasswordResetConfirmRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetSmsSendRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetSmsVerifyRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetTokenResponse;
import kr.silverbridge.main.domain.auth.service.PasswordResetService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "비밀번호 재설정", description = "비밀번호 찾기 및 재설정 API (이메일 방식 / SMS 방식)")
@RestController
@RequestMapping("/api/auth/password")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    @Operation(summary = "[이메일 방식] 비밀번호 재설정 이메일 발송", description = "입력한 이메일로 비밀번호 재설정 안내 메일을 보냅니다. 보안을 위해 해당 이메일이 존재하지 않아도 동일하게 응답합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요청 처리 완료 (이메일이 없어도 동일하게 200 반환)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "이메일 형식 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PostMapping("/reset-request")
    public ApiResponse<Void> requestReset(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.requestReset(request);
        return ApiResponse.ok("비밀번호 재설정 이메일이 발송되었습니다.");
    }

    @Operation(summary = "[SMS 방식] 비밀번호 재설정 인증코드 발송", description = "이름과 전화번호로 가입 여부를 확인 후 인증코드를 SMS로 보냅니다. 보안을 위해 해당 정보가 없어도 동일하게 응답합니다. 인증코드는 5분간 유효하며, 재발송은 1분 후 가능합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요청 처리 완료 (사용자가 없어도 동일하게 200 반환)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 오류 또는 재발송 제한 중"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "SMS 발송 실패")
    })
    @PostMapping("/sms/send")
    public ApiResponse<Void> sendSms(@Valid @RequestBody PasswordResetSmsSendRequest request) {
        passwordResetService.requestResetBySms(request);
        return ApiResponse.ok("비밀번호 재설정 인증코드가 발송되었습니다.");
    }

    @Operation(summary = "[SMS 방식] 인증코드 확인 및 재설정 코드 발급", description = "SMS로 받은 인증코드를 확인합니다. 인증 성공 시 비밀번호를 변경할 수 있는 재설정 코드(token)가 발급됩니다. 재설정 코드는 30분간 유효합니다. 5회 이상 틀리면 인증코드가 초기화됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "인증 성공, 재설정 코드 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "인증코드 불일치, 만료, 또는 5회 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음")
    })
    @PostMapping("/sms/verify")
    public ApiResponse<PasswordResetTokenResponse> verifySms(@Valid @RequestBody PasswordResetSmsVerifyRequest request) {
        PasswordResetTokenResponse response = passwordResetService.verifySmsAndIssueToken(request);
        return ApiResponse.ok(response);
    }

    @Operation(summary = "새 비밀번호 설정", description = "이메일 또는 SMS 방식으로 발급된 재설정 코드(token)와 새 비밀번호를 입력합니다. 변경 성공 시 모든 기기에서 자동으로 로그아웃됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "비밀번호 변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "재설정 코드 만료 또는 입력값 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PostMapping("/reset")
    public ApiResponse<Void> confirmReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.confirmReset(request);
        return ApiResponse.ok("비밀번호가 변경되었습니다.");
    }
}

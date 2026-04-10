package kr.silverbridge.main.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.auth.dto.PasswordResetConfirmRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetSmsSendRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetSmsVerifyRequest;
import kr.silverbridge.main.domain.auth.service.PasswordResetService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "비밀번호 재설정", description = "비밀번호 재설정 API. 이메일 방식과 SMS 방식 중 선택 가능하며, 두 방식 모두 최종 단계는 POST /api/auth/password/reset 으로 동일합니다.")
@RestController
@RequestMapping("/api/auth/password")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    @Operation(
            summary = "[이메일 방식 1단계] 비밀번호 재설정 링크 이메일 발송",
            description = """
                    가입된 이메일로 비밀번호 재설정 링크를 발송합니다.
                    사용자가 이메일의 링크를 클릭하면 비밀번호 재설정 페이지로 이동합니다.

                    [이메일 방식 전체 흐름]
                    1. POST /api/auth/password/reset-request    → 재설정 링크 이메일 발송
                    2. 사용자가 이메일의 링크 클릭
                       → https://dmu.gosky.kr/reset-password?token={token} 으로 이동
                    3. POST /api/auth/password/reset            → URL의 token + 새 비밀번호로 변경

                    [주의사항]
                    - 링크 유효 시간: 30분
                    - 보안상 이유로 해당 이메일이 존재하지 않아도 200을 반환합니다.
                    - 카카오로 가입한 계정은 발송되지 않습니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요청 처리 완료 (이메일 미존재 또는 카카오 계정이어도 동일하게 200 반환)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "이메일 형식 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PostMapping("/reset-request")
    public ApiResponse<Void> requestReset(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.requestReset(request);
        return ApiResponse.ok("비밀번호 재설정 이메일이 발송되었습니다.");
    }

    @Operation(
            summary = "[SMS 방식 1단계] 비밀번호 재설정 인증코드 발송",
            description = """
                    이름과 전화번호로 가입 여부를 확인 후 인증코드를 SMS로 발송합니다.

                    [SMS 방식 전체 흐름]
                    1. POST /api/auth/password/sms/send      → 인증코드 SMS 발송
                    2. POST /api/auth/password/sms/verify    → 인증코드 확인
                       → 인증 성공 시 재설정 링크를 SMS로 발송
                          (https://dmu.gosky.kr/reset-password?token={token})
                    3. 사용자가 SMS의 링크 클릭
                       → https://dmu.gosky.kr/reset-password?token={token} 으로 이동
                    4. POST /api/auth/password/reset         → URL의 token + 새 비밀번호로 변경

                    [주의사항]
                    - 인증코드 유효 시간: 5분 / 재발송 가능 시간: 1분 후
                    - 5회 이상 오류 시 인증코드 초기화 → 재발송 필요
                    - 보안상 이유로 일치하는 계정이 없어도 200을 반환합니다.
                    - 카카오로 가입한 계정은 발송되지 않습니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요청 처리 완료 (일치하는 계정 없거나 카카오 계정이어도 동일하게 200 반환)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 오류 또는 재발송 제한 중 (1분 이내 재발송 불가)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "SMS 발송 실패")
    })
    @PostMapping("/sms/send")
    public ApiResponse<Void> sendSms(@Valid @RequestBody PasswordResetSmsSendRequest request) {
        passwordResetService.requestResetBySms(request);
        return ApiResponse.ok("비밀번호 재설정 인증코드가 발송되었습니다.");
    }

    @Operation(
            summary = "[SMS 방식 2단계] 인증코드 확인 및 재설정 링크 SMS 발송",
            description = """
                    SMS로 받은 인증코드를 확인합니다.
                    인증 성공 시 비밀번호 재설정 링크를 SMS로 발송합니다.
                    사용자가 SMS의 링크를 클릭하면 비밀번호 재설정 페이지로 이동합니다.

                    발송되는 링크 형식: https://dmu.gosky.kr/reset-password?token={token}
                    → 프론트엔드에서 URL의 token 파라미터를 읽어 POST /api/auth/password/reset 에 전달하세요.

                    [제한사항]
                    - 재설정 링크 유효 시간: 30분
                    - 5회 이상 오류 시 인증코드 초기화 → 인증코드 재발송 필요
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "인증 성공. 재설정 링크를 SMS로 발송함"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "인증코드 불일치 / 인증코드 만료 / 5회 이상 오류로 인증코드 초기화됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "SMS 발송 실패")
    })
    @PostMapping("/sms/verify")
    public ApiResponse<Void> verifySms(@Valid @RequestBody PasswordResetSmsVerifyRequest request) {
        passwordResetService.verifySmsAndSendLink(request);
        return ApiResponse.ok("비밀번호 재설정 링크가 발송되었습니다.");
    }

    @Operation(
            summary = "[공통 마지막 단계] 새 비밀번호 설정",
            description = """
                    이메일 또는 SMS로 받은 재설정 링크의 token 값과 새 비밀번호를 입력하여 비밀번호를 변경합니다.
                    변경 성공 시 모든 기기에서 자동 로그아웃됩니다. (재로그인 필요)

                    [token 가져오는 방법]
                    재설정 링크: https://dmu.gosky.kr/reset-password?token={token}
                    → URL에서 token 쿼리 파라미터 값을 그대로 전달하세요.

                    [비밀번호 조건]
                    - 숫자·특수문자 포함, 공백 없이 8자 이상
                    - 현재 비밀번호와 동일 불가
                    - 최근 사용한 비밀번호 2개와 동일 불가
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "비밀번호 변경 성공. 모든 기기에서 로그아웃됨 → 재로그인 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "token 만료 또는 유효하지 않음 / 현재 또는 최근 사용한 비밀번호와 동일 / 비밀번호 형식 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PostMapping("/reset")
    public ApiResponse<Void> confirmReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.confirmReset(request);
        return ApiResponse.ok("비밀번호가 변경되었습니다.");
    }
}

package kr.silverbridge.main.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.auth.dto.PasswordResetConfirmRequest;
import kr.silverbridge.main.domain.auth.service.PasswordResetService;
import kr.silverbridge.main.global.response.ApiResponse;
import kr.silverbridge.main.global.security.RateLimitService;
import kr.silverbridge.main.global.util.ClientIpResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증")
@RestController
@RequestMapping("/api/auth/password")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;
    private final RateLimitService rateLimitService;

    @Operation(
            summary = "[공통] 3단계 · 새 비밀번호 설정",
            description = """
                    앞의 '인증코드 사전 확인' 단계에서 검증한 6자리 인증코드를 그대로 다시 전달하여 비밀번호를 변경합니다. (UUID 토큰 없음)
                    변경 성공 시 모든 기기에서 자동 로그아웃됩니다. (재로그인 필요)

                    [요청 필드]
                    - 이메일 방식: email + code(6자리) + newPassword   (phone은 비움)
                    - SMS 방식:   phone + code(6자리) + newPassword   (email은 비움)
                    - email/phone 은 정확히 하나만 채워야 합니다.
                    - code: 1·2단계에서 받은/확인한 그 6자리 숫자 (유효 5분, 5회 오류 시 무효화)

                    [왜 email|phone·code를 또 보내나요?]
                    비로그인 흐름이라 인증 토큰이 없고, 사전 확인(/verify)은 서버에 상태를 남기지 않습니다.
                    서버가 "누구의" 비밀번호인지 알고 인증 사실을 재확인하려면 이 단계에서 식별자(email|phone)와
                    6자리 코드를 함께 받아 재검증해야 합니다. (code만/newPassword만으로는 대상 특정 불가)

                    [비밀번호 조건]
                    - 영문·숫자·특수문자 모두 포함, 공백 없이 8자 이상
                    - 현재 비밀번호와 동일 불가
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "비밀번호 변경 성공. 모든 기기에서 로그아웃됨 → 재로그인 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "email/phone 동시 지정 또는 둘 다 누락 / 코드 만료·불일치·5회초과 / 현재 비밀번호와 동일 / 입력 형식 오류 / 카카오 계정", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "동일 IP의 과도한 요청 (1분 10회)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류", content = @Content)
    })
    @PostMapping("/reset")
    public ApiResponse<Void> confirmReset(@Valid @RequestBody PasswordResetConfirmRequest request,
                                          HttpServletRequest httpRequest) {
        rateLimitService.check("pw-reset-confirm", ClientIpResolver.resolve(httpRequest));
        passwordResetService.confirmReset(
                request,
                ClientIpResolver.resolve(httpRequest),
                httpRequest.getHeader("User-Agent")
        );
        return ApiResponse.ok("비밀번호가 변경되었습니다.");
    }
}

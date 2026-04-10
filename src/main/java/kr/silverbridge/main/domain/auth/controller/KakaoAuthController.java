package kr.silverbridge.main.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.auth.dto.KakaoLoginRequest;
import kr.silverbridge.main.domain.auth.dto.KakaoLoginResponse;
import kr.silverbridge.main.domain.auth.dto.KakaoRegisterRequest;
import kr.silverbridge.main.domain.auth.dto.LoginResponse;
import kr.silverbridge.main.domain.auth.service.KakaoAuthService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "카카오 인증", description = "카카오 로그인 및 회원가입 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class KakaoAuthController {

    private final KakaoAuthService kakaoAuthService;

    @Operation(
            summary = "카카오 로그인",
            description = """
                    카카오에서 받은 인가 코드로 로그인합니다.
                    - 기존 회원: 바로 로그인 처리됩니다. (isNewUser=false)
                    - 신규 회원: 전화번호 인증 후 회원가입을 완료해야 합니다. (isNewUser=true)
                      → 전화번호 입력 → SMS 인증 → POST /api/auth/kakao/register 호출
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "인가 코드 누락"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "카카오 인가 코드가 만료되었거나 유효하지 않음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "비활성화된 계정"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 사용 중인 이메일"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PostMapping("/kakao")
    public ApiResponse<KakaoLoginResponse> kakaoLogin(@Valid @RequestBody KakaoLoginRequest request,
                                                      HttpServletRequest httpRequest) {
        return ApiResponse.ok(kakaoAuthService.kakaoLogin(
                request,
                httpRequest.getRemoteAddr(),
                httpRequest.getHeader("User-Agent")
        ));
    }

    @Operation(
            summary = "카카오 신규 회원가입 완료",
            description = """
                    카카오 로그인 후 신규 사용자의 회원가입을 완료합니다.
                    - SMS 인증 완료 후 호출해야 합니다.
                    - kakaoId: 카카오 로그인 응답에서 받은 값을 그대로 사용하세요.
                    - 완료 후 바로 로그인 상태가 됩니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "회원가입 완료, 로그인 처리됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "SMS 인증 미완료 또는 카카오 로그인 세션 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 사용 중인 이메일"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PostMapping("/kakao/register")
    public ApiResponse<LoginResponse> kakaoRegister(@Valid @RequestBody KakaoRegisterRequest request,
                                                    HttpServletRequest httpRequest) {
        return ApiResponse.ok(kakaoAuthService.kakaoRegister(
                request,
                httpRequest.getRemoteAddr(),
                httpRequest.getHeader("User-Agent")
        ));
    }
}

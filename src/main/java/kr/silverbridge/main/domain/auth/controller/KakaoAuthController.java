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

@Tag(name = "인증")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class KakaoAuthController {

    private final KakaoAuthService kakaoAuthService;

    @Operation(
            summary = "카카오 로그인",
            description = """
                    카카오 OAuth 인가 코드로 로그인합니다.
                    isNewUser 값에 따라 이후 처리가 달라집니다.

                    [기존 회원 — isNewUser=false]
                    accessToken, refreshToken이 반환됩니다. 바로 로그인 처리하세요.
                    → Header: Authorization: Bearer {accessToken}

                    [신규 회원 — isNewUser=true]
                    kakaoId, email, name, profileImageUrl만 반환됩니다. 토큰 없음.
                    회원가입 폼에 값을 자동 입력하고, 아래 흐름을 이어서 진행하세요.

                    [카카오 신규 회원가입 전체 흐름]
                    1. POST /api/auth/kakao             → 카카오 로그인 (isNewUser=true 확인)
                    2. POST /api/auth/sms/send          → SMS 인증코드 발송
                    3. POST /api/auth/sms/verify        → SMS 인증코드 확인
                    4. POST /api/auth/kakao/register    → 회원가입 완료 (accessToken, refreshToken 발급)
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공. isNewUser=false면 accessToken+refreshToken, true면 kakaoId+email+name 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "인가 코드 누락"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "카카오 인가 코드가 만료되었거나 유효하지 않음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "비활성화된 계정"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "동일 이메일로 이미 일반 가입된 계정이 존재함"),
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
                    카카오 로그인(isNewUser=true) 후 SMS 인증까지 완료한 신규 사용자의 회원가입을 완료합니다.
                    성공 시 accessToken, refreshToken이 발급되어 바로 로그인 상태가 됩니다.

                    [요청 전 확인사항]
                    - POST /api/auth/kakao 에서 받은 kakaoId를 그대로 전달해야 합니다.
                    - SMS 인증(POST /api/auth/sms/verify)이 완료된 전화번호를 사용해야 합니다.
                    - kakaoId는 서버에서 10분간 유지됩니다. 10분 초과 시 카카오 로그인부터 다시 진행하세요.

                    [토큰 사용 방법]
                    - accessToken: 이후 모든 API 요청 헤더에 포함
                      → Header: Authorization: Bearer {accessToken}
                    - refreshToken: accessToken 만료(1시간) 시 POST /api/auth/refresh 로 재발급
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "회원가입 완료. accessToken, refreshToken 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "SMS 인증 미완료, 10분 초과로 인증 만료, 또는 입력값 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 사용 중인 이메일 (전화번호 중복은 SMS 발송 단계에서 먼저 반환됨)"),
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

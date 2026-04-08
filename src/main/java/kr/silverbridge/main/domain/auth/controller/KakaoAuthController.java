package kr.silverbridge.main.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.auth.dto.KakaoLoginRequest;
import kr.silverbridge.main.domain.auth.dto.KakaoLoginResponse;
import kr.silverbridge.main.domain.auth.dto.KakaoRoleRequest;
import kr.silverbridge.main.domain.auth.dto.LoginResponse;
import kr.silverbridge.main.domain.auth.service.KakaoAuthService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "카카오 인증", description = "카카오 OAuth 로그인 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class KakaoAuthController {

    private final KakaoAuthService kakaoAuthService;

    @Operation(
            summary = "카카오 로그인",
            description = """
                    카카오 OAuth 인가 코드로 로그인합니다.
                    - 기존 사용자: isNewUser=false, accessToken+refreshToken 반환
                    - 신규 사용자: isNewUser=true, accessToken만 반환 (refreshToken=null)
                      → 역할 선택 필요: POST /api/auth/kakao/role 호출
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "인가 코드 누락"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "유효하지 않은 카카오 인가 코드"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "비활성화된 계정"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 사용 중인 이메일"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
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
            summary = "카카오 신규 가입 역할 선택",
            description = """
                    카카오 최초 로그인(isNewUser=true) 후 역할을 선택합니다.
                    - 카카오 로그인에서 받은 accessToken으로 인증 필요
                    - WARD(피보호자) 또는 GUARDIAN(보호자) 중 하나 선택
                    - 완료 후 정식 accessToken + refreshToken 발급
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "역할 선택 완료, 정식 토큰 발급"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "role 값 누락 또는 ADMIN 선택 시도"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 유효하지 않음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @SecurityRequirement(name = "Bearer Authentication")
    @PostMapping("/kakao/role")
    public ApiResponse<LoginResponse> completeRole(@Valid @RequestBody KakaoRoleRequest request,
                                                   @AuthenticationPrincipal String userId,
                                                   HttpServletRequest httpRequest) {
        return ApiResponse.ok(kakaoAuthService.completeRole(
                userId,
                request.getRole(),
                httpRequest.getRemoteAddr(),
                httpRequest.getHeader("User-Agent")
        ));
    }
}

package kr.gosky.sso.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.gosky.sso.domain.auth.dto.FindEmailRequest;
import kr.gosky.sso.domain.auth.dto.FindEmailResponse;
import kr.gosky.sso.domain.auth.dto.LoginRequest;
import kr.gosky.sso.domain.auth.dto.LoginResponse;
import kr.gosky.sso.domain.auth.dto.RegisterRequest;
import kr.gosky.sso.domain.auth.dto.TokenRefreshRequest;
import kr.gosky.sso.domain.auth.dto.TokenRefreshResponse;
import kr.gosky.sso.domain.auth.service.AuthService;
import kr.gosky.sso.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "인증", description = "회원가입, 로그인, 로그아웃, 토큰 갱신, 아이디 찾기 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "회원가입", description = "이메일과 비밀번호로 새 계정을 생성합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "회원가입 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 유효성 검증 실패 (이메일 형식 오류, 비밀번호 8자 미만 등)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 사용 중인 이메일")
    })
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ApiResponse.ok("회원가입이 완료되었습니다.");
    }

    @Operation(summary = "로그인", description = "이메일과 비밀번호로 로그인하여 Access Token과 Refresh Token을 발급받습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공, Access Token / Refresh Token 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 유효성 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "비밀번호 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "비활성화된 계정"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 이메일")
    })
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.login(
                request,
                httpRequest.getRemoteAddr(),
                httpRequest.getHeader("User-Agent")
        ));
    }

    @Operation(summary = "토큰 갱신", description = "Refresh Token으로 만료된 Access Token을 재발급받습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "새 Access Token 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "유효하지 않은 Refresh Token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음")
    })
    @PostMapping("/refresh")
    public ApiResponse<TokenRefreshResponse> refresh(@Valid @RequestBody TokenRefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request));
    }

    @Operation(summary = "로그아웃", description = "현재 Access Token을 무효화하고 Refresh Token을 삭제합니다. 이후 해당 토큰으로 API 호출 시 401이 반환됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그아웃 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "유효하지 않거나 만료된 토큰")
    })
    @SecurityRequirement(name = "Bearer Authentication")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader("Authorization") String bearerToken,
                                    @AuthenticationPrincipal String userId,
                                    HttpServletRequest httpRequest) {
        authService.logout(
                bearerToken.substring(7),
                userId,
                httpRequest.getRemoteAddr(),
                httpRequest.getHeader("User-Agent")
        );
        return ApiResponse.ok("로그아웃되었습니다.");
    }

    @Operation(summary = "아이디(이메일) 찾기", description = "이름과 전화번호가 일치하는 계정의 이메일을 마스킹하여 반환합니다. (예: us**@example.com)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "마스킹된 이메일 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 유효성 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "이름과 전화번호가 일치하는 계정 없음")
    })
    @PostMapping("/find-email")
    public ApiResponse<FindEmailResponse> findEmail(@Valid @RequestBody FindEmailRequest request) {
        return ApiResponse.ok(authService.findEmail(request));
    }
}

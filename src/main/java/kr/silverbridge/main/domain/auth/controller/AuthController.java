package kr.silverbridge.main.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.auth.dto.EmailCheckRequest;
import kr.silverbridge.main.domain.auth.dto.FindEmailRequest;
import kr.silverbridge.main.domain.auth.dto.FindEmailResponse;
import kr.silverbridge.main.domain.auth.dto.LoginRequest;
import kr.silverbridge.main.domain.auth.dto.LoginResponse;
import kr.silverbridge.main.domain.auth.dto.RegisterRequest;
import kr.silverbridge.main.domain.auth.dto.TokenRefreshRequest;
import kr.silverbridge.main.domain.auth.dto.TokenRefreshResponse;
import kr.silverbridge.main.domain.auth.service.AuthService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "인증", description = "회원가입, 로그인, 로그아웃, 아이디 찾기 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "이메일 중복 확인", description = "회원가입 전 이메일이 이미 사용 중인지 확인합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "사용 가능한 이메일"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "이메일 형식이 올바르지 않음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 사용 중인 이메일"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PostMapping("/email/check")
    public ApiResponse<Void> checkEmail(@Valid @RequestBody EmailCheckRequest request) {
        authService.checkEmail(request);
        return ApiResponse.ok("사용 가능한 이메일입니다.");
    }

    @Operation(summary = "회원가입", description = "이메일과 비밀번호로 새 계정을 만듭니다. SMS 인증 완료 후 호출해야 합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "회원가입 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 오류 (이메일 형식, 비밀번호 8자 미만 등) 또는 SMS 인증 미완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 사용 중인 이메일"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ApiResponse.ok("회원가입이 완료되었습니다.");
    }

    @Operation(summary = "로그인", description = "이메일과 비밀번호로 로그인합니다. 로그인 성공 시 API 호출에 필요한 인증 토큰이 발급됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "비밀번호 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "비활성화된 계정"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 이메일"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류")
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

    @Operation(summary = "인증 토큰 갱신", description = "로그인 시 발급된 갱신 토큰(refreshToken)으로 만료된 인증 토큰을 새로 발급받습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "새 인증 토큰 발급 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "갱신 토큰 누락"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "갱신 토큰이 만료되었거나 유효하지 않음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PostMapping("/refresh")
    public ApiResponse<TokenRefreshResponse> refresh(@Valid @RequestBody TokenRefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request));
    }

    @Operation(summary = "로그아웃", description = "현재 로그인 상태를 종료합니다. 로그아웃 후에는 모든 기기에서 재로그인이 필요합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그아웃 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "인증 토큰 누락"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰이 만료되었거나 유효하지 않음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류")
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

    @Operation(summary = "아이디(이메일) 찾기", description = "이름과 전화번호로 가입된 이메일을 조회합니다. 보안을 위해 이메일 일부는 가려져 반환됩니다. (예: us**@example.com)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "이메일 조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "이름과 전화번호가 일치하는 계정 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PostMapping("/find-email")
    public ApiResponse<FindEmailResponse> findEmail(@Valid @RequestBody FindEmailRequest request) {
        return ApiResponse.ok(authService.findEmail(request));
    }
}

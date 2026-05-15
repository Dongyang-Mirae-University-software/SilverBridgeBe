package kr.silverbridge.main.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
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
import kr.silverbridge.main.global.security.RateLimitService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "인증", description = """
        회원가입, 로그인, 토큰 재발급, 비밀번호 재설정 등 인증 관련 API.

        [공통 응답 포맷]
        모든 API는 아래 구조로 응답합니다.
        { "code": 200, "message": "성공", "data": { ... } }

        [토큰 인증이 필요한 API]
        로그인 후 발급된 accessToken을 Swagger UI 우측 상단 Authorize 버튼에 입력하면
        이후 모든 인증 필요 API에 자동 적용됩니다.
        Header: Authorization: Bearer {accessToken}
        """)
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RateLimitService rateLimitService;

    @Operation(
            summary = "이메일 중복 확인",
            description = """
                    회원가입 전 이메일이 이미 사용 중인지 확인합니다.
                    사용 가능하면 200, 이미 존재하면 409를 반환합니다.

                    [일반 회원가입 전체 흐름]
                    1. POST /api/auth/signup/email/check → 이메일 중복 확인
                    2. POST /api/auth/signup/sms/send    → SMS 인증코드 발송
                    3. POST /api/auth/signup/sms/verify  → SMS 인증코드 확인
                    4. POST /api/auth/signup             → 회원가입 완료
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "사용 가능한 이메일"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "이메일 형식이 올바르지 않음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 사용 중인 이메일", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류", content = @Content)
    })
    @PostMapping("/signup/email/check")
    public ApiResponse<Void> checkEmail(@Valid @RequestBody EmailCheckRequest request,
                                        HttpServletRequest httpRequest) {
        rateLimitService.check("email-check", httpRequest.getRemoteAddr());
        authService.checkEmail(request);
        return ApiResponse.ok("사용 가능한 이메일입니다.");
    }

    @Operation(
            summary = "회원가입",
            description = """
                    이메일·비밀번호로 새 계정을 생성합니다.
                    반드시 SMS 인증(POST /api/auth/signup/sms/verify) 완료 후 호출해야 합니다.

                    [주의사항]
                    - SMS 인증 완료 후 10분 이내에 호출해야 합니다.
                    - 비밀번호: 숫자·특수문자 포함, 공백 없이 8자 이상
                    - role: WARD(피보호자) 또는 GUARDIAN(보호자) 중 하나 선택
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "회원가입 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 오류 또는 SMS 인증 미완료 (10분 초과 시 만료)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 사용 중인 이메일", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류", content = @Content)
    })
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ApiResponse.ok("회원가입이 완료되었습니다.");
    }

    @Operation(
            summary = "로그인",
            description = """
                    이메일·비밀번호로 로그인합니다.
                    성공 시 accessToken과 refreshToken이 발급됩니다.

                    [토큰 사용 방법]
                    - accessToken: 이후 모든 API 요청 시 헤더에 포함
                      → Header: Authorization: Bearer {accessToken}
                    - refreshToken: accessToken 만료(30분) 시 POST /api/auth/refresh 로 재발급
                      → refreshToken 유효 시간: 7일
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공. accessToken, refreshToken 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "이메일 또는 비밀번호가 올바르지 않음 (가입 안 된 이메일과 비밀번호 불일치를 동일 응답으로 통합 — 계정 enumeration 차단)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "비활성화된 계정 (비밀번호 검증을 통과한 본인에게만 노출)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "비밀번호 5회 이상 틀려 30분 잠금 상태", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류", content = @Content)
    })
    @PostMapping("/signin")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.login(
                request,
                httpRequest.getRemoteAddr(),
                httpRequest.getHeader("User-Agent")
        ));
    }

    @Operation(
            summary = "Access Token 재발급",
            description = """
                    Access Token이 만료(30분)된 경우 Refresh Token으로 새 토큰을 발급받습니다.
                    Refresh Token Rotation 적용 — accessToken과 refreshToken이 모두 새로 발급됩니다.

                    [주의사항]
                    - 기존 refreshToken은 즉시 무효화됩니다. 응답의 새 refreshToken으로 반드시 교체 저장하세요.
                    - Refresh Token도 만료(7일)된 경우 401이 반환되며, 재로그인이 필요합니다.
                    - 새로 발급된 accessToken으로 Authorization 헤더를 업데이트하세요.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "새 Access Token 발급 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "refreshToken 값 누락", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Refresh Token이 만료되었거나 유효하지 않음 → 재로그인 필요", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류", content = @Content)
    })
    @PostMapping("/refresh")
    public ApiResponse<TokenRefreshResponse> refresh(@Valid @RequestBody TokenRefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request));
    }

    @Operation(
            summary = "로그아웃",
            description = """
                    현재 로그인 상태를 종료합니다.
                    모든 기기에서 로그아웃됩니다 (Refresh Token 삭제).

                    [요청 헤더]
                    Authorization: Bearer {accessToken}
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그아웃 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Authorization 헤더 누락", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Access Token이 만료되었거나 유효하지 않음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류", content = @Content)
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

    @Operation(
            summary = "아이디(이메일) 찾기",
            description = """
                    이름과 전화번호로 가입된 계정을 조회합니다.

                    [응답 구조]
                    - maskedEmail: 일반(LOCAL) 계정이 있으면 마스킹된 이메일 반환, 없으면 null
                      예: us**@example.com
                    - hasKakaoAccount: 카카오(KAKAO) 계정이 있으면 true → "카카오 계정이 존재합니다" 표시

                    [케이스별 동작]
                    - 일반 계정만 있는 경우: maskedEmail=값, hasKakaoAccount=false
                    - 카카오 계정만 있는 경우: maskedEmail=null, hasKakaoAccount=true
                    - 둘 다 있는 경우: maskedEmail=값, hasKakaoAccount=true
                    - 계정 없음: 404 반환
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "계정 조회 성공. maskedEmail 또는 hasKakaoAccount(또는 둘 다) 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "이름과 전화번호가 일치하는 계정 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류", content = @Content)
    })
    @PostMapping("/find-email")
    public ApiResponse<FindEmailResponse> findEmail(@Valid @RequestBody FindEmailRequest request,
                                                    HttpServletRequest httpRequest) {
        rateLimitService.check("find-email", httpRequest.getRemoteAddr());
        return ApiResponse.ok(authService.findEmail(request));
    }
}

package kr.silverbridge.main.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
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
import kr.silverbridge.main.global.security.RateLimitService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "인증")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class KakaoAuthController {

    private final KakaoAuthService kakaoAuthService;
    private final RateLimitService rateLimitService;

    @Operation(
            summary = "카카오 로그인",
            description = """
                    카카오 OAuth 인가 코드로 로그인합니다.
                    isNewUser 값에 따라 이후 처리가 달라집니다.

                    [기존 회원 — isNewUser=false]
                    accessToken, refreshToken이 반환됩니다. 바로 로그인 처리하세요.
                    → Header: Authorization: Bearer {accessToken}

                    [신규 회원 — isNewUser=true] 토큰 없음. 다음 값이 반환됩니다.
                    - email: 카카오 계정 이메일(account_email). 우리 서비스의 계정 ID(로그인 이메일)로 사용 → 회원가입 폼에 자동 입력
                      (카카오에서 이메일을 제공하지 않으면 임시 이메일이 발급될 수 있음)
                    - profileImageUrl: 카카오 프로필 사진(profile_image). 우리 프로필 이미지로 사용
                    - name: 항상 null. 카카오 닉네임(profile_nickname)은 사용하지 않음 → 사용자가 본인 실명을 직접 입력
                    - kakaoId: 4단계 가입 완료 요청에 그대로 전달

                    [카카오 신규 회원가입 전체 흐름]
                    1. POST /api/auth/signin/kakao      → 카카오 로그인 (isNewUser=true 확인)
                    2. POST /api/auth/signup/sms/send   → SMS 인증코드 발송
                    3. POST /api/auth/signup/sms/verify → SMS 인증코드 확인 → verificationNonce 수령
                    4. POST /api/auth/signup/kakao      → 본인 실명·성별·생년월일·주소 입력하여 가입 완료 (accessToken, refreshToken 발급)
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공. isNewUser=false면 accessToken+refreshToken, true면 kakaoId+email+name 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "인가 코드 누락", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "카카오 인가 코드가 만료되었거나 유효하지 않음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "비활성화된 계정", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "동일 이메일로 이미 일반 가입된 계정이 존재함", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류", content = @Content)
    })
    @PostMapping("/signin/kakao")
    public ApiResponse<KakaoLoginResponse> kakaoLogin(@Valid @RequestBody KakaoLoginRequest request,
                                                      HttpServletRequest httpRequest) {
        rateLimitService.check("kakao-login", httpRequest.getRemoteAddr());
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

                    [요청 body]
                    - kakaoId           : POST /api/auth/signin/kakao 응답값 그대로
                    - name              : 본인 실명 직접 입력 (카카오 닉네임 사용 불가), 최대 20자
                    - phone             : SMS 인증 완료한 번호 (숫자 10~11자리)
                    - verificationNonce : POST /api/auth/signup/sms/verify 응답의 data.verificationNonce
                    - role              : WARD(피보호자) 또는 GUARDIAN(보호자)
                    - gender            : FEMALE / MALE
                    - birthDate         : yyyy-MM-dd, 미래 불가·만 14세 이상
                    - postcode/address/addressDetail : 카카오 주소 검색 결과 (우편번호 5자리 + 도로명 + 상세)
                    - profileImageUrl   : 선택 (signin/kakao 응답의 profileImageUrl 또는 null)
                    (email은 보내지 않습니다 — 서버가 카카오 계정 이메일로 자동 설정)

                    [요청 전 확인사항]
                    - kakaoId는 서버에서 10분간 유지됩니다. 10분 초과 시 카카오 로그인부터 다시 진행하세요.
                    - SMS 인증(POST /api/auth/signup/sms/verify)이 완료된 전화번호여야 합니다.

                    [토큰 사용 방법]
                    - accessToken: 이후 모든 API 요청 헤더에 포함
                      → Header: Authorization: Bearer {accessToken}
                    - refreshToken: accessToken 만료(30분) 시 POST /api/auth/refresh 로 재발급
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "회원가입 완료. accessToken, refreshToken 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "SMS 인증 미완료, 10분 초과로 인증 만료, 또는 입력값 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 사용 중인 이메일 (전화번호 중복은 SMS 발송 단계에서 먼저 반환됨)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류", content = @Content)
    })
    @PostMapping("/signup/kakao")
    public ApiResponse<LoginResponse> kakaoRegister(@Valid @RequestBody KakaoRegisterRequest request,
                                                    HttpServletRequest httpRequest) {
        return ApiResponse.ok(kakaoAuthService.kakaoRegister(
                request,
                httpRequest.getRemoteAddr(),
                httpRequest.getHeader("User-Agent")
        ));
    }
}

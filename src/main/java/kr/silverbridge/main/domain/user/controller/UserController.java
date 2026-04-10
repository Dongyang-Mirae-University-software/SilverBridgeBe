package kr.silverbridge.main.domain.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.user.dto.PasswordChangeRequest;
import kr.silverbridge.main.domain.user.dto.UserProfileResponse;
import kr.silverbridge.main.domain.user.dto.UserUpdateRequest;
import kr.silverbridge.main.domain.user.dto.WithdrawRequest;
import kr.silverbridge.main.domain.user.service.UserService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "사용자", description = "내 정보 조회, 비밀번호 변경, 회원 탈퇴 API")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
public class UserController {

    private final UserService userService;

    @Operation(summary = "내 정보 조회", description = "로그인한 사용자의 프로필 정보를 반환합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "프로필 정보 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> getMyProfile(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(userService.getMyProfile(userId));
    }

    @Operation(
            summary = "내 정보 수정",
            description = """
                    이름과 전화번호를 수정합니다.

                    [전화번호 변경 시 필수 절차]
                    전화번호는 실제 본인 소유 번호인지 검증하기 위해 SMS 인증이 필요합니다.
                    변경할 번호로 SMS 인증을 먼저 완료한 후 이 API를 호출하세요.

                    1. POST /api/auth/sms/send    → 새 전화번호로 SMS 인증코드 발송
                    2. POST /api/auth/sms/verify  → 인증코드 확인 (10분 유효)
                    3. PUT  /api/users/me         → 인증된 새 번호로 정보 수정

                    전화번호를 변경하지 않는 경우 phone 필드를 생략하거나 기존 번호를 그대로 전달하면 됩니다.

                    [요청 헤더]
                    Authorization: Bearer {accessToken}
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정된 프로필 정보 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 오류 또는 전화번호 변경 시 SMS 인증 미완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PutMapping("/me")
    public ApiResponse<UserProfileResponse> updateProfile(@AuthenticationPrincipal String userId,
                                                          @Valid @RequestBody UserUpdateRequest request) {
        return ApiResponse.ok(userService.updateProfile(userId, request));
    }

    @Operation(
            summary = "비밀번호 변경 (로그인 상태)",
            description = """
                    현재 비밀번호를 알고 있는 로그인된 사용자가 새 비밀번호로 변경합니다.
                    비밀번호를 잊어버린 경우에는 이 API가 아닌 POST /api/auth/password/reset-request 또는 POST /api/auth/password/sms/send 를 사용하세요.

                    변경 성공 시 모든 기기에서 자동 로그아웃됩니다. (재로그인 필요)

                    [요청 헤더]
                    Authorization: Bearer {accessToken}
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "비밀번호 변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 유효성 검증 실패 (새 비밀번호 8자 미만 등)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "현재 비밀번호 불일치 또는 인증 토큰 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PutMapping("/me/password")
    public ApiResponse<Void> changePassword(@AuthenticationPrincipal String userId,
                                            @Valid @RequestBody PasswordChangeRequest request) {
        userService.changePassword(userId, request.getCurrentPassword(), request.getNewPassword());
        return ApiResponse.ok("비밀번호가 변경되었습니다. 다시 로그인해주세요.");
    }

    @Operation(summary = "회원 탈퇴", description = "비밀번호를 확인한 후 계정을 비활성화합니다. 탈퇴 후 해당 계정으로 로그인이 불가합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "회원 탈퇴 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 유효성 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "비밀번호 불일치 또는 인증 토큰 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @DeleteMapping("/me")
    public ApiResponse<Void> withdraw(@AuthenticationPrincipal String userId,
                                      @Valid @RequestBody WithdrawRequest request) {
        userService.withdraw(userId, request.getPassword());
        return ApiResponse.ok("회원 탈퇴가 완료되었습니다.");
    }
}

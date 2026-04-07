package kr.gosky.sso.domain.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.gosky.sso.domain.user.dto.PasswordChangeRequest;
import kr.gosky.sso.domain.user.dto.UserProfileResponse;
import kr.gosky.sso.domain.user.dto.WithdrawRequest;
import kr.gosky.sso.domain.user.service.UserService;
import kr.gosky.sso.global.response.ApiResponse;
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
            @ApiResponse(responseCode = "200", description = "프로필 정보 반환"),
            @ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음")
    })
    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> getMyProfile(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(userService.getMyProfile(userId));
    }

    @Operation(summary = "비밀번호 변경", description = "현재 비밀번호를 확인한 후 새 비밀번호로 변경합니다. 변경 후 모든 기기에서 자동 로그아웃됩니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "비밀번호 변경 성공"),
            @ApiResponse(responseCode = "400", description = "입력값 유효성 검증 실패 (새 비밀번호 8자 미만 등)"),
            @ApiResponse(responseCode = "401", description = "현재 비밀번호 불일치 또는 인증 토큰 만료"),
            @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음")
    })
    @PutMapping("/me/password")
    public ApiResponse<Void> changePassword(@AuthenticationPrincipal String userId,
                                            @Valid @RequestBody PasswordChangeRequest request) {
        userService.changePassword(userId, request.getCurrentPassword(), request.getNewPassword());
        return ApiResponse.ok("비밀번호가 변경되었습니다. 다시 로그인해주세요.");
    }

    @Operation(summary = "회원 탈퇴", description = "비밀번호를 확인한 후 계정을 비활성화합니다. 탈퇴 후 해당 계정으로 로그인이 불가합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회원 탈퇴 완료"),
            @ApiResponse(responseCode = "400", description = "입력값 유효성 검증 실패"),
            @ApiResponse(responseCode = "401", description = "비밀번호 불일치 또는 인증 토큰 만료"),
            @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음")
    })
    @DeleteMapping("/me")
    public ApiResponse<Void> withdraw(@AuthenticationPrincipal String userId,
                                      @Valid @RequestBody WithdrawRequest request) {
        userService.withdraw(userId, request.getPassword());
        return ApiResponse.ok("회원 탈퇴가 완료되었습니다.");
    }
}

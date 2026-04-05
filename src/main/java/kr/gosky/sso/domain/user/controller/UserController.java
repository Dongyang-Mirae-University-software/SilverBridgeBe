package kr.gosky.sso.domain.user.controller;

import io.swagger.v3.oas.annotations.Operation;
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

@Tag(name = "User", description = "사용자 관리 API")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
public class UserController {

    private final UserService userService;

    // 내 정보 조회
    @Operation(summary = "내 정보 조회", description = "로그인한 사용자의 프로필 정보를 반환합니다.")
    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> getMyProfile(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(userService.getMyProfile(userId));
    }

    // 비밀번호 변경
    @Operation(summary = "비밀번호 변경", description = "현재 비밀번호 확인 후 새 비밀번호로 변경합니다. 변경 후 모든 기기에서 로그아웃됩니다.")
    @PutMapping("/me/password")
    public ApiResponse<Void> changePassword(@AuthenticationPrincipal String userId,
                                            @Valid @RequestBody PasswordChangeRequest request) {
        userService.changePassword(userId, request.getCurrentPassword(), request.getNewPassword());
        return ApiResponse.ok("비밀번호가 변경되었습니다. 다시 로그인해주세요.");
    }

    // 회원 탈퇴
    @Operation(summary = "회원 탈퇴", description = "비밀번호 확인 후 계정을 비활성화합니다.")
    @DeleteMapping("/me")
    public ApiResponse<Void> withdraw(@AuthenticationPrincipal String userId,
                                      @Valid @RequestBody WithdrawRequest request) {
        userService.withdraw(userId, request.getPassword());
        return ApiResponse.ok("회원 탈퇴가 완료되었습니다.");
    }
}

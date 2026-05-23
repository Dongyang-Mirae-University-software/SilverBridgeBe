package kr.silverbridge.main.domain.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.user.dto.PasswordChangeRequest;
import kr.silverbridge.main.domain.user.dto.UserProfileResponse;
import kr.silverbridge.main.domain.user.dto.UserUpdateRequest;
import kr.silverbridge.main.domain.user.dto.WithdrawRequest;
import kr.silverbridge.main.domain.user.service.UserService;
import kr.silverbridge.main.global.response.ApiResponse;
import kr.silverbridge.main.global.util.ClientIpResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "사용자", description = """
        로그인한 사용자 본인의 프로필 조회/수정, 비밀번호 변경, 회원 탈퇴 API.
        모든 요청에 Authorization 헤더가 필요합니다: Authorization: Bearer {accessToken}
        """)
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
public class UserController {

    private final UserService userService;

    @Operation(
            summary = "내 정보 조회",
            description = """
                    로그인한 사용자의 프로필 정보를 반환합니다.

                    [응답 data 필드]
                    id, email, name, phone, provider(LOCAL/KAKAO), role(WARD/GUARDIAN/ADMIN),
                    profileImage, gender(FEMALE/MALE), birthDate(yyyy-MM-dd), postcode,
                    address, addressDetail, lastLoginAt, createdAt(가입일시)

                    [기존 사용자 주의]
                    프로필 필드가 추가되기 전 가입한 사용자는 gender/birthDate/postcode가 null일 수 있습니다.
                    null이면 프로필 수정 화면에서 보완 입력을 유도하세요. (PUT /api/user/me 에서 필수)

                    [요청 헤더]
                    Authorization: Bearer {accessToken}
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "프로필 정보 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    }) @GetMapping("/me")
    public ApiResponse<UserProfileResponse> getMyProfile(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(userService.getMyProfile(userId));
    }

    @Operation(
            summary = "내 정보 수정",
            description = """
                    프로필을 수정합니다. 응답으로 수정된 전체 프로필을 반환합니다.

                    [요청 body — 모두 필수, 변경 없는 값도 현재 값 그대로 전송]
                    - name        : 본인 실명, 최대 20자
                    - phone       : 숫자 10~11자리 (변경 안 해도 현재 번호를 그대로 전송 — 생략 불가)
                    - gender      : FEMALE / MALE
                    - birthDate   : yyyy-MM-dd, 미래 불가·만 14세 이상
                    - postcode    : 우편번호 5자리 (카카오 주소 검색)
                    - address     : 도로명/지번 주소
                    - addressDetail: 상세 주소
                    - verificationNonce : 전화번호를 "변경할 때만" 필수 (아래 절차), 그대로면 생략/null

                    ※ 기존 사용자(gender/birthDate/postcode가 null이던 계정)는 이 API에서 해당 값을 반드시 채워야 합니다.

                    [전화번호를 변경하는 경우에만]
                    새 번호 소유 검증을 위해 SMS 인증 후 호출해야 합니다.
                    1. POST /api/auth/signup/sms/send    → 새 전화번호로 인증코드 발송
                    2. POST /api/auth/signup/sms/verify  → 코드 확인 → verificationNonce 수령 (10분 유효)
                    3. PUT  /api/user/me                 → 새 phone + 위 verificationNonce 포함하여 호출
                    (번호를 바꾸지 않으면 현재 번호를 phone에 그대로 넣고 verificationNonce는 생략)

                    [요청 헤더] Authorization: Bearer {accessToken}
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정된 프로필 정보 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 오류(필수 누락, 생년월일 미래/14세 미만, 우편번호 형식 등) 또는 전화번호 변경 시 SMS 인증 미완료/만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 사용 중인 전화번호", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류", content = @Content)
    })
    @PutMapping("/me")
    public ApiResponse<UserProfileResponse> updateProfile(@AuthenticationPrincipal String userId,
                                                          @Valid @RequestBody UserUpdateRequest request) {
        return ApiResponse.ok(userService.updateProfile(userId, request));
    }

    @Operation(
            summary = "프로필 이미지 변경",
            description = """
                    프로필 이미지를 업로드하여 변경합니다.
                    이미지 파일을 multipart/form-data 형식으로 전송하면 파일 서버에 업로드 후 URL이 저장됩니다.

                    [요청 헤더]
                    Authorization: Bearer {accessToken}
                    Content-Type: multipart/form-data

                    [파라미터]
                    - file: 업로드할 이미지 파일 (form-data)
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "프로필 이미지 변경 성공, 수정된 프로필 정보 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "파일 없음 또는 잘못된 요청", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "파일 업로드 실패 또는 서버 오류", content = @Content)
    })
    @PatchMapping(value = "/me/image", consumes = "multipart/form-data")
    public ApiResponse<UserProfileResponse> updateProfileImage(@AuthenticationPrincipal String userId,
                                                               @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(userService.updateProfileImage(userId, file));
    }

    @Operation(
            summary = "비밀번호 변경 (로그인 상태)",
            description = """
                    현재 비밀번호를 알고 있는 로그인된 사용자가 새 비밀번호로 변경합니다.
                    비밀번호를 잊어버린 경우에는 이 API가 아닌 POST /api/auth/find-password/email/send 또는 POST /api/auth/find-password/sms/send 를 사용하세요.

                    - newPassword 규칙: 영문·숫자·특수문자 모두 포함, 공백 없이 8자 이상. 현재 비밀번호와 동일 불가.
                    - 카카오(KAKAO) 가입 계정은 비밀번호가 없어 이 API를 사용할 수 없습니다(400).
                    - 변경 성공 시 모든 기기에서 자동 로그아웃됩니다. (재로그인 필요)

                    [요청 헤더]
                    Authorization: Bearer {accessToken}
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "비밀번호 변경 성공 → 재로그인 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "비밀번호 형식 위반(영문+숫자+특수문자 8자+ 아님) / 현재 비밀번호와 동일 / 카카오 계정", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "현재 비밀번호 불일치 또는 인증 토큰 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PutMapping("/me/password")
    public ApiResponse<Void> changePassword(@AuthenticationPrincipal String userId,
                                            @Valid @RequestBody PasswordChangeRequest request) {
        userService.changePassword(userId, request.getCurrentPassword(), request.getNewPassword());
        return ApiResponse.ok("비밀번호가 변경되었습니다. 다시 로그인해주세요.");
    }

    @Operation(
            summary = "회원 탈퇴",
            description = """
                    본인 확인 후 계정을 비활성화합니다. 탈퇴 후 해당 계정으로 로그인이 불가합니다.

                    [본인 확인 방식]
                    - 일반(LOCAL) 가입자: password 필수
                    - 카카오(KAKAO) 가입자: confirmation 필수 — 사용자가 화면에서 정확히 "탈퇴"를 입력해 전달
                      (access token 단독 탈취로 인한 영구 비활성화 위험 차단)

                    [요청 헤더]
                    Authorization: Bearer {accessToken}
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "회원 탈퇴 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 유효성 검증 실패 또는 카카오 사용자 confirmation 불일치", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "비밀번호 불일치 또는 인증 토큰 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @DeleteMapping("/me")
    public ApiResponse<Void> withdraw(@AuthenticationPrincipal String userId,
                                      @Valid @RequestBody WithdrawRequest request,
                                      HttpServletRequest httpRequest) {
        userService.withdraw(
                userId,
                request.getPassword(),
                request.getConfirmation(),
                ClientIpResolver.resolve(httpRequest),
                httpRequest.getHeader("User-Agent")
        );
        return ApiResponse.ok("회원 탈퇴가 완료되었습니다.");
    }
}

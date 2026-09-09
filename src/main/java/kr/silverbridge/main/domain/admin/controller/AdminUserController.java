package kr.silverbridge.main.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.admin.dto.AdminUserConnectionFilter;
import kr.silverbridge.main.domain.admin.dto.AdminUserCountsResponse;
import kr.silverbridge.main.domain.admin.dto.AdminUserDetailResponse;
import kr.silverbridge.main.domain.admin.dto.AdminUserListItem;
import kr.silverbridge.main.domain.admin.dto.AdminUserStatusFilter;
import kr.silverbridge.main.domain.admin.dto.AdminUserUpdateRequest;
import kr.silverbridge.main.domain.admin.service.AdminUserService;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.response.ApiResponse;
import kr.silverbridge.main.global.response.PageResponse;
import kr.silverbridge.main.global.util.ClientIpResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 회원관리 API.
 *
 * <p>인가는 {@code SecurityConfig}의 {@code /api/admin/**} 경로 규칙과 클래스 레벨 {@code @PreAuthorize}가
 * 이중으로 담당한다. 경로 규칙만 두면 "관리자 아닌 역할 차단"을 테스트로 고정할 수 없고 경로가 바뀌면 조용히 열린다.</p>
 *
 * <p><b>관리자 계정은 조회만 된다.</b> 목록·탭 건수에는 나오지만 수정·삭제 요청은 403이다.</p>
 */
@Tag(name = "관리자 - 회원관리")
@RestController
@RequestMapping("/api/admin/user")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService adminUserService;

    @Operation(summary = "회원 목록 조회 (검색·필터·페이징)",
            description = """
                    회원을 가입일 최신순으로 조회합니다. 관리자 계정도 포함됩니다.

                    [검색 범위]
                    keyword 는 이름·이메일·전화번호에 더해 **연결된 상대의 이름**까지 찾습니다.
                    (예: "홍길동"으로 검색하면 홍길동 본인과, 홍길동과 연결된 피보호자들이 함께 나옵니다)

                    [연결 상태]
                    한 회원이 연결됨과 수락 대기를 동시에 가질 수 있어 우선순위로 하나를 고릅니다.
                    ACTIVE가 하나라도 있으면 CONNECTED, 없고 PENDING만 있으면 PENDING, 둘 다 없으면 NONE입니다.
                    관리자 계정은 연결 축이 없어 connectionState 가 **null** 이며, 연결 필터를 걸면 목록에서 빠집니다.

                    [목록에 없는 것]
                    탈퇴가 진행 중인 계정은 나오지 않습니다. 관리자가 할 수 있는 일이 없고 곧 사라지는 임시 상태입니다.

                    [연결 요약]
                    행마다 연결 건수와 대표 1명만 옵니다. 전체 목록은 상세 조회를 쓰세요.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping
    public ApiResponse<PageResponse<AdminUserListItem>> getUsers(
            @Parameter(description = "검색어 (이름·이메일·전화번호·연결 회원 이름). 생략 시 전체")
            @RequestParam(required = false) String keyword,

            @Parameter(description = "역할 탭 (생략 시 전체)")
            @RequestParam(required = false) Role role,

            @Parameter(description = "계정 상태 필터 (생략 시 ALL)")
            @RequestParam(required = false) AdminUserStatusFilter status,

            @Parameter(description = "연결 상태 필터 (생략 시 ALL)")
            @RequestParam(required = false) AdminUserConnectionFilter connection,

            @Parameter(description = "페이지 번호 (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (최대 50)") @RequestParam(defaultValue = "20") int size) {

        return ApiResponse.ok(adminUserService.getUsers(keyword, role, status, connection, page, size));
    }

    @Operation(summary = "회원 탭별 건수",
            description = """
                    전체·보호자·피보호자·관리자 건수를 반환합니다. 목록과 같은 모집단(탈퇴 진행 중 계정 제외)입니다.

                    0건인 역할도 키가 항상 존재합니다(탭은 늘 네 개이고, "관리자 0명"은 사실 그대로의 정보입니다).
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "건수 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/counts")
    public ApiResponse<AdminUserCountsResponse> getCounts() {
        return ApiResponse.ok(adminUserService.getCounts());
    }

    @Operation(summary = "회원 상세 조회",
            description = """
                    회원 한 명의 상세와 **연결 전체 목록**을 반환합니다.

                    [관계(relation)의 방향에 주의]
                    relation 은 연결을 요청한 **보호자가 피보호자에게 어떤 사람인지**를 가리킵니다("아들", "며느리").
                    저장은 한 방향뿐이라 반대 라벨은 존재하지 않습니다.
                    counterpartRole 이 GUARDIAN 이면 relation 은 상대방을, WARD 이면 조회 대상 회원을 가리킵니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "상세 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "회원을 찾을 수 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/{userId}")
    public ApiResponse<AdminUserDetailResponse> getUser(
            @Parameter(description = "회원 ID (6자리)", example = "EE81BF") @PathVariable String userId) {
        return ApiResponse.ok(adminUserService.getUser(userId));
    }

    @Operation(summary = "회원 정보 수정 (이름·역할·계정 상태)",
            description = """
                    세 필드 모두 선택이며 **null 은 "변경하지 않음"** 입니다. 화면의 저장 한 번에 대응합니다.

                    [이메일·전화번호는 바꿀 수 없습니다]
                    전화번호는 본인이 바꿀 때 SMS 인증이 필수라 관리자 경로를 열면 그 인증을 우회하게 되고,
                    잘못 넣으면 SOS·복약 문자가 다른 번호로 갑니다. 이메일은 본인조차 바꿀 수 없는 로그인 ID입니다.

                    [역할을 바꾸면 기존 연결이 해제됩니다]
                    보호자-피보호자 방향이 뒤집혀 관계가 뜻을 잃기 때문입니다.
                    연결돼 있던 상대에게는 해제 알림이 나가고, 수락 전 요청은 조용히 취소됩니다.
                    ADMIN 으로는 바꿀 수 없습니다(400).

                    [계정 상태]
                    ACTIVE(이용 중) ↔ RESTRICTED(이용 제한)만 오갈 수 있습니다.
                    이용 제한으로 바꾸면 로그인·토큰 재발급이 막히고 **이미 발급된 토큰도 즉시 무효화**되며,
                    **그 계정으로는 어떤 알림도 나가지 않습니다**(SOS·이상감지의 강제 푸시 포함).
                    INACTIVE 는 400 입니다 - 탈퇴는 상태 변경이 아니라 삭제(회원 강제 탈퇴)입니다.

                    [피보호자는 이용 제한할 수 없습니다] (400)
                    로그인이 막히면 긴급 도움 요청(SOS)을 보낼 수 없게 되기 때문입니다.
                    피보호자 계정은 편의 기능이 아니라 안전망 그 자체라, 탈취가 의심되면
                    정지 대신 비밀번호 재설정으로 세션만 끊는 것이 맞습니다.
                    **정지된 보호자를 피보호자로 바꾸는 것도 같은 이유로 400**입니다(두 단계 우회 차단).
                    다만 제한을 풀면서 동시에 역할을 바꾸는 요청은 정상 처리됩니다.

                    [정지 사유]
                    statusReason 은 선택이며 RESTRICTED 로 바꿀 때만 저장됩니다(최대 200자).
                    ACTIVE 로 되돌리면 지워집니다. 목록·상세 응답의 statusReason 으로 확인할 수 있습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 유효성 실패 / ADMIN 역할 지정 / INACTIVE 상태 지정 / 피보호자 이용 제한 시도", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음 또는 관리자 계정 변경 시도", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "회원을 찾을 수 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PatchMapping("/{userId}")
    public ApiResponse<Void> updateUser(
            @Parameter(description = "회원 ID (6자리)", example = "EE81BF") @PathVariable String userId,
            @Valid @RequestBody AdminUserUpdateRequest request,
            @AuthenticationPrincipal String adminId) {

        adminUserService.updateUser(userId, request, adminId);
        return ApiResponse.ok("회원 정보가 수정되었습니다.");
    }

    @Operation(summary = "회원 강제 탈퇴",
            description = """
                    회원을 **영구 삭제**합니다(복구 불가). 일반 탈퇴와 같은 경로를 타므로
                    연결 해제와 상대방 알림, 토큰 정리, 접속 로그 기록이 모두 함께 처리됩니다.

                    연결·FCM·refresh 토큰은 함께 삭제되고, 접속 로그는 익명으로 보존됩니다(감사 목적).
                    같은 이메일·전화번호로 재가입할 수 있게 됩니다.

                    관리자 계정은 삭제할 수 없습니다(403).
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "탈퇴 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음 또는 관리자 계정 삭제 시도", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "회원을 찾을 수 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @DeleteMapping("/{userId}")
    public ApiResponse<Void> forceDelete(
            @Parameter(description = "회원 ID (6자리)", example = "EE81BF") @PathVariable String userId,
            @AuthenticationPrincipal String adminId,
            HttpServletRequest httpRequest) {

        adminUserService.forceDelete(userId, adminId,
                ClientIpResolver.resolve(httpRequest), httpRequest.getHeader("User-Agent"));
        return ApiResponse.ok("회원이 삭제되었습니다.");
    }
}

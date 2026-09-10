package kr.silverbridge.main.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.admin.dto.AdminConnectionResponse;
import kr.silverbridge.main.domain.admin.dto.AdminForceConnectRequest;
import kr.silverbridge.main.domain.admin.service.AdminConnectionService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 강제 연결·해제 API. 회원관리 상세 모달의 "연결" 영역에서 호출한다.
 *
 * <p>연결 <b>조회</b> API는 두지 않았다 - 회원 상세({@code GET /api/admin/user/{userId}})가 이미
 * 그 회원의 연결 전체를 돌려준다. 별도 "연결 관리" 화면이 생기면 그때 추가한다.</p>
 */
@Tag(name = "관리자 - 연결 관리")
@RestController
@RequestMapping("/api/admin/connection")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@PreAuthorize("hasRole('ADMIN')")
public class AdminConnectionController {

    private final AdminConnectionService adminConnectionService;

    @Operation(summary = "강제 연결",
            description = """
                    관리자가 보호자와 피보호자를 **직접 연결**합니다. 고객센터 문의를 받아 처리하는 경로입니다.
                    (시니어가 수락 버튼을 누르지 못해 가족이 연결하지 못하는 경우가 실제로 있습니다.)

                    [⚠️ 동의 없이 관계가 생깁니다]
                    일반 연결은 피보호자의 **수락이 곧 동의**인데, 강제 연결은 그 동의 없이
                    SOS·카메라·복약·위치 이력을 열어 줍니다.
                    화면에 **확인 다이얼로그**를 두고, 무엇이 공개되는지 밝혀 주세요.

                    [양쪽 모두에게 알림이 갑니다]
                    보호자 - "관리자가 OOO님과의 연결을 완료했습니다."
                    피보호자 - "관리자가 OOO님을 보호자로 연결했습니다."
                    피보호자는 자기도 모르게 연결된 것이라 반드시 알아야 하기 때문입니다.

                    [수락 대기 중인 요청이 있으면 그것을 승격시킵니다]
                    같은 쌍의 PENDING 요청이 있으면 새로 만들지 않고 그 요청을 연결 상태로 바꿉니다.
                    관리자가 **대신 수락해 주는** 셈입니다.

                    [연결할 수 없는 경우]
                    역할이 맞지 않으면(보호자↔피보호자가 아니면) 400,
                    이용 제한·탈퇴 처리 중인 계정이면 400, 이미 연결돼 있으면 409입니다.

                    관계(아들·딸 등)는 받지 않습니다 - 관리자는 가족 관계를 알 수 없고,
                    그 값은 보호자가 직접 요청할 때만 입력됩니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "연결 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "역할 불일치 / 이용 제한·탈퇴 처리 중인 계정 / 입력값 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "회원을 찾을 수 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 연결된 관계", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PostMapping
    public ApiResponse<AdminConnectionResponse> forceConnect(
            @Valid @RequestBody AdminForceConnectRequest request,
            @AuthenticationPrincipal String adminId) {

        return ApiResponse.ok(adminConnectionService.forceConnect(request, adminId));
    }

    @Operation(summary = "강제 연결 해제",
            description = """
                    관리자가 연결을 **직접 해제**합니다. 연결된(ACTIVE) 관계만 대상입니다.

                    [양쪽 모두에게 알림이 갑니다]
                    "관리자가 연결을 해제했습니다."
                    당사자가 끊는 경우와 달리 **둘 다 자기가 끊지 않았으므로**, 한쪽만 알리면
                    나머지 한 명은 연결이 사라진 것을 모릅니다.

                    수락 대기 중인 요청은 이 API로 취소할 수 없습니다(409).
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "해제 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "연결 관계를 찾을 수 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "연결된(ACTIVE) 관계가 아님", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @DeleteMapping("/{connectionId}")
    public ApiResponse<Void> forceDisconnect(
            @Parameter(description = "연결 ID", example = "42") @PathVariable Long connectionId,
            @AuthenticationPrincipal String adminId) {

        adminConnectionService.forceDisconnect(connectionId, adminId);
        return ApiResponse.ok("연결이 해제되었습니다.");
    }
}

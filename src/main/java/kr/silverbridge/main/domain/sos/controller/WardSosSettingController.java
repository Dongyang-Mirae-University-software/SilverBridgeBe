package kr.silverbridge.main.domain.sos.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.sos.dto.SosSettingResponse;
import kr.silverbridge.main.domain.sos.dto.SosSettingUpdateRequest;
import kr.silverbridge.main.domain.sos.service.SosSettingService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 피보호자 SOS 동작 설정 API. SOS는 피보호자 전용 기능이라 WARD만 접근 가능하다(GUARDIAN/ADMIN 403).
 */
@Tag(name = "피보호자 - SOS")
@RestController
@RequestMapping("/api/ward/sos-setting")
@RequiredArgsConstructor
@PreAuthorize("hasRole('WARD')")
@SecurityRequirement(name = "Bearer Authentication")
public class WardSosSettingController {

    private final SosSettingService sosSettingService;

    @Operation(
            summary = "SOS 동작 설정 조회 (피보호자 전용)",
            description = """
                    로그인한 피보호자의 SOS 동작 설정을 반환합니다.

                    [응답 data]
                    sosAction: CALL_119 / CALL_119_AND_NOTIFY / NOTIFY_GUARDIAN_FIRST

                    [값의 의미 - "119 안내 화면을 언제 어떻게 보여줄지"]
                    - CALL_119              : 119 화면 바로 표시 (버튼을 누르면 곧바로 119 키패드 화면)
                    - CALL_119_AND_NOTIFY   : 119 화면 + 보호자 알림 안내 (기본값)
                    - NOTIFY_GUARDIAN_FIRST : 보호자에게 먼저 알린 뒤 119 화면으로 안내

                    [⚠️ 중요]
                    이 설정으로 보호자 알림을 끌 수 없습니다. SOS는 생명과 직결된 필수 알림이라
                    어떤 값이든 보호자 알림은 항상 발송됩니다. CALL_119도 "보호자 알림 없이"라는 뜻이 아닙니다.
                    세 값의 차이는 119 안내 화면의 표시 시점·방식뿐입니다.

                    [기본값] 설정한 적 없으면 CALL_119_AND_NOTIFY로 응답합니다.

                    [범위 밖 - 프론트 처리] 119 화면은 프론트가 그립니다.
                    ⚠️ 실제 119로 전화를 걸지 않습니다 - 119가 입력된 키패드만 보여주고 발신은 하지 않습니다
                    (2026-08-26 결정). 값 이름의 CALL_119는 "전화를 건다"가 아니라 "119 화면을 띄운다"는 뜻입니다.

                    [요청 헤더] Authorization: Bearer {accessToken}
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "SOS 동작 설정 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "피보호자(WARD)가 아닌 계정", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping
    public ApiResponse<SosSettingResponse> getSetting(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(sosSettingService.getSetting(userId));
    }

    @Operation(
            summary = "SOS 동작 설정 변경 (피보호자 전용)",
            description = """
                    피보호자의 SOS 동작 설정을 변경합니다. 계정에 저장되므로 기기·브라우저를 바꿔도 유지됩니다.

                    [요청 body]
                    sosAction: CALL_119 / CALL_119_AND_NOTIFY / NOTIFY_GUARDIAN_FIRST (필수)

                    응답으로 변경 후 설정을 반환합니다.

                    [참고] 정의되지 않은 값을 보내면 400입니다.

                    [요청 헤더] Authorization: Bearer {accessToken}
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "변경된 SOS 동작 설정 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 오류(sosAction 누락 또는 정의되지 않은 값)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "피보호자(WARD)가 아닌 계정", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PutMapping
    public ApiResponse<SosSettingResponse> updateSetting(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody SosSettingUpdateRequest request) {
        return ApiResponse.ok(sosSettingService.updateSetting(userId, request));
    }
}

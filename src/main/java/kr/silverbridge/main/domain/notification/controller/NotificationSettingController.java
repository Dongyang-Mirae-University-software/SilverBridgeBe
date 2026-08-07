package kr.silverbridge.main.domain.notification.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.notification.dto.NotificationSettingResponse;
import kr.silverbridge.main.domain.notification.dto.NotificationSettingUpdateRequest;
import kr.silverbridge.main.domain.notification.service.NotificationSettingService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "공통 - 알림 설정")
@RestController
@RequestMapping("/api/user/me/notification-settings")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
public class NotificationSettingController {

    private final NotificationSettingService settingService;

    @Operation(
            summary = "알림 설정 조회",
            description = """
                    로그인한 사용자의 전체 알림 채널 설정을 반환합니다.

                    [응답 data]
                    settings: [{ channelType, enabled }, ...] — 구현 여부와 무관하게 전체 채널 노출
                      - channelType: FCM / SMS / KAKAO_ALIMTALK / EMAIL
                      - enabled    : 활성화 여부

                    [기본값]
                    설정한 적 없는 채널은 기본값으로 표시됩니다 — FCM은 기본 ON, 나머지는 기본 OFF.
                    (EMAIL은 발송 미구현. KAKAO_ALIMTALK은 승인된 템플릿이 있는 알림 종류만 발송됩니다.)

                    [요청 헤더] Authorization: Bearer {accessToken}
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "알림 설정 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping
    public ApiResponse<NotificationSettingResponse> getSettings(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(settingService.getSettings(userId));
    }

    @Operation(
            summary = "알림 설정 변경",
            description = """
                    알림 채널의 ON/OFF를 변경합니다. 전달한 채널만 갱신되며, 생략한 채널은 기존 값(또는 기본값)을 유지합니다.

                    [요청 body]
                    settings: [{ channelType, enabled }, ...] — 하나 이상 필수
                      - channelType: FCM / SMS / KAKAO_ALIMTALK / EMAIL (필수)
                      - enabled    : true / false (필수)

                    응답으로 변경 후 전체 채널 설정을 반환합니다.

                    [참고]
                    - SMS 인증번호 등 인증 알림은 이 설정과 무관하게 항상 발송됩니다(끌 수 없음).
                    - EMAIL은 설정값만 저장되며 실제 발송은 추후 단계에서 지원됩니다.

                    [요청 헤더] Authorization: Bearer {accessToken}
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "변경된 전체 알림 설정 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 오류(settings 비어 있음, channelType/enabled 누락 등)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PutMapping
    public ApiResponse<NotificationSettingResponse> updateSettings(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody NotificationSettingUpdateRequest request) {
        return ApiResponse.ok(settingService.updateSettings(userId, request));
    }
}
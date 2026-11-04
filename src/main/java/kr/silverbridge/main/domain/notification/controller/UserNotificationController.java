package kr.silverbridge.main.domain.notification.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.notification.dto.UserNotificationCategory;
import kr.silverbridge.main.domain.notification.dto.UserNotificationItem;
import kr.silverbridge.main.domain.notification.dto.UserNotificationReadAllResponse;
import kr.silverbridge.main.domain.notification.dto.UserNotificationUnreadCountResponse;
import kr.silverbridge.main.domain.notification.service.UserNotificationService;
import kr.silverbridge.main.global.response.ApiResponse;
import kr.silverbridge.main.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 알림 기록(웹 상단 종 아이콘) - 보호자·피보호자 (2026-11-04).
 *
 * <p>관리자는 알림을 받지 않으므로 403이다. 사용자 ID는 토큰에서만 받는다.</p>
 */
@Tag(name = "공통 - 내 알림 기록")
@RestController
@RequestMapping("/api/user/me/notifications")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('GUARDIAN', 'WARD')")
@SecurityRequirement(name = "Bearer Authentication")
public class UserNotificationController {

    private static final String SCOPE = """
            [보이는 알림] 최근 90일 동안 **실제로 보낸** 알림만 보입니다.
              - 포함: 발송 서버가 접수한 알림, SOS 문자 대체, 모든 채널이 실패한 알림(기기에 닿지 않았을 수 있음)
              - 제외: 보내지 않은 알림(사용자가 채널을 꺼 둠·이용 제한 등)
              - 웹소켓으로만 가는 화면 갱신 이벤트와 인증번호 문자는 기록되지 않습니다.
            연결이 끝난 피보호자에 관한 알림도 이미 받은 알림이라 그대로 보입니다(피보호자가 탈퇴하면 함께 지워집니다).
            """;

    private final UserNotificationService userNotificationService;

    @Operation(summary = "내 알림 목록", description = SCOPE + """

            [정렬] 최신순 (발송 시각 내림차순)
            [category] URGENT(SOS·이상감지 발생·안부 말벗 위험 신호) / MEDICATION(복약 시각·미복용 요약·복약 일정 중지)
              / CONNECTION(연결 요청·수락·거절·해제·관리자 연결) / OTHER(이상감지 판정 요청·재확인·카메라 연결 확인·문의 답변·안부 말벗 안내·기분 변화)
            [항목] id, type, category, title, body, createdAt, read, wardId, wardName
              - title·body는 휴대폰에 표시된 문구와 같습니다.
              - wardId·wardName은 다른 사람(피보호자)에 관한 알림일 때만 있습니다. 본인에 관한 알림·문의 답변·판정 하루 요약은 null.
            [요청 헤더] Authorization: Bearer {accessToken}
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "목록 (PageResponse)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "category 값이 잘못됨", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자·피보호자가 아님(관리자)", content = @Content)
    })
    @GetMapping
    public ApiResponse<PageResponse<UserNotificationItem>> getMyNotifications(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "알림 묶음 (생략 시 전체)") @RequestParam(required = false) UserNotificationCategory category,
            @Parameter(description = "페이지 번호 (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (기본 20, 최대 50)") @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(userNotificationService.getMyNotifications(userId, category, page, size));
    }

    @Operation(summary = "안 읽은 알림 수", description = SCOPE + """

            종 아이콘 뱃지용. 화면 진입·창 포커스·푸시 수신 때 다시 불러 주세요(실시간 이벤트는 없습니다).
            [요청 헤더] Authorization: Bearer {accessToken}
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "{count}"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자·피보호자가 아님(관리자)", content = @Content)
    })
    @GetMapping("/unread-count")
    public ApiResponse<UserNotificationUnreadCountResponse> countUnread(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(userNotificationService.countUnread(userId));
    }

    @Operation(summary = "알림 한 건 읽음", description = """
            본인이 받은 알림만 읽음 처리합니다. 이미 읽은 알림이면 그대로 200입니다(처음 읽은 시각 유지).
            [요청 헤더] Authorization: Bearer {accessToken}
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "읽음 처리됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "NOTIFICATION_NOT_OWNED - 다른 사용자의 알림 / 관리자", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "NOTIFICATION_NOT_FOUND - 없는 알림(보관 기간이 지나 지워진 알림 포함)", content = @Content)
    })
    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(@AuthenticationPrincipal String userId, @PathVariable Long id) {
        userNotificationService.markRead(userId, id);
        return ApiResponse.ok("알림을 읽음 처리했습니다.");
    }

    @Operation(summary = "알림 모두 읽음", description = """
            본인의 안 읽은 알림을 모두 읽음 처리합니다. 응답 updated = 이번에 바뀐 건수.
            [요청 헤더] Authorization: Bearer {accessToken}
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "{updated}"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자·피보호자가 아님(관리자)", content = @Content)
    })
    @PostMapping("/read-all")
    public ApiResponse<UserNotificationReadAllResponse> markAllRead(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(userNotificationService.markAllRead(userId));
    }
}

package kr.silverbridge.main.domain.notification.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import kr.silverbridge.main.domain.notification.dto.FcmTokenRegisterRequest;
import kr.silverbridge.main.domain.notification.dto.FcmTokenReleaseRequest;
import kr.silverbridge.main.domain.notification.service.FcmService;
import kr.silverbridge.main.domain.notification.service.FcmTokenReleaseService;
import kr.silverbridge.main.global.response.ApiResponse;
import kr.silverbridge.main.global.security.RateLimitService;
import kr.silverbridge.main.global.util.ClientIpResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "공통 - 알림 설정")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final FcmService fcmService;
    private final FcmTokenReleaseService fcmTokenReleaseService;
    private final RateLimitService rateLimitService;

    @Operation(summary = "FCM 토큰 등록",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    앱 시작 시 또는 FCM 토큰 갱신 시 호출합니다.
                    이미 등록된 토큰이면 무시합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "FCM 토큰 등록 완료 (이미 등록된 토큰이어도 200 반환)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "token 또는 platform 필드 누락", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "인증 토큰 없음 또는 만료", content = @Content)
    })
    @PostMapping("/fcm-token")
    public ResponseEntity<ApiResponse<Void>> registerFcmToken(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody FcmTokenRegisterRequest request) {
        // 동일 사용자의 FCM 토큰 등록 스팸 방지
        rateLimitService.check("fcm-register", userId);
        fcmService.registerToken(userId, request.getToken(), request.getPlatform());
        return ResponseEntity.ok(ApiResponse.ok("FCM 토큰이 등록되었습니다."));
    }

    @Operation(summary = "FCM 토큰 삭제 (로그아웃 시)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    로그아웃 시 해당 디바이스의 FCM 토큰을 삭제합니다.
                    이후 해당 디바이스로 푸시 알림이 전송되지 않습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "FCM 토큰 삭제 완료 (존재하지 않는 토큰이어도 200 반환)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "token 쿼리 파라미터 누락", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "인증 토큰 없음 또는 만료", content = @Content)
    })
    @DeleteMapping("/fcm-token")
    public ResponseEntity<ApiResponse<Void>> deleteFcmToken(
            @AuthenticationPrincipal String userId,
            @RequestParam String token) {
        // 본인 소유 토큰만 삭제 — 타인 토큰 값 무단 삭제 차단 (L-S2-3)
        fcmService.deleteToken(userId, token);
        return ResponseEntity.ok(ApiResponse.ok("FCM 토큰이 삭제되었습니다."));
    }

    @Operation(summary = "FCM 토큰 해제 (세션 만료·자동 로그아웃 시)",
            description = """
                    [인증 헤더 없음] - Authorization 헤더를 보내지 마세요(만료된 헤더는 인증 필터가 401로 막습니다).

                    세션이 만료돼 자동 로그아웃될 때, 이 기기로 이전 사용자의 알림이 계속 오지 않도록
                    이 기기의 FCM 토큰을 지웁니다. 로그인 상태의 로그아웃에서는 기존 DELETE /api/notifications/fcm-token 을 쓰세요.

                    [요청 본문]
                    accessToken: 마지막으로 쓰던 access token (만료돼도 됨, 쿼리 문자열이 아니라 본문으로)
                    token: 이 기기의 FCM 토큰

                    access token의 주인이 등록한 토큰만 지웁니다. 다른 사람 소유이거나 이미 없는 토큰이면 아무것도 하지 않고 200입니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "처리 완료 (지울 토큰이 없어도 200)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "accessToken 또는 token 누락", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "서버가 발급한 access token이 아니거나 너무 오래 전에 만료됨", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
                    description = "동일 IP의 과도한 요청 (1분 10회)", content = @Content)
    })
    @PostMapping("/fcm-token/release")
    public ResponseEntity<ApiResponse<Void>> releaseFcmToken(
            @Valid @RequestBody FcmTokenReleaseRequest request,
            HttpServletRequest httpRequest) {
        rateLimitService.check("fcm-release", ClientIpResolver.resolve(httpRequest));
        fcmTokenReleaseService.release(request.getAccessToken(), request.getToken());
        return ResponseEntity.ok(ApiResponse.ok("FCM 토큰을 해제했습니다."));
    }
}

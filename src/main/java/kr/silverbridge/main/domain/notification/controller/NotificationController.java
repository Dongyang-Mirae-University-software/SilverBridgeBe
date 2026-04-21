package kr.silverbridge.main.domain.notification.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.notification.dto.FcmTokenRegisterRequest;
import kr.silverbridge.main.domain.notification.service.FcmService;
import kr.silverbridge.main.global.response.ApiResponse;
import kr.silverbridge.main.global.security.RateLimitService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "알림")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final FcmService fcmService;
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
        fcmService.deleteToken(token);
        return ResponseEntity.ok(ApiResponse.ok("FCM 토큰이 삭제되었습니다."));
    }
}

package kr.silverbridge.main.domain.notification.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.notification.dto.FcmTestRequest;
import kr.silverbridge.main.domain.notification.service.FcmService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FCM 발송 로직 점검용 개발 전용 컨트롤러.
 * app.dev-tools.enabled=true 일 때만 빈 등록되며, 프로덕션에서는 비활성.
 */
@Tag(name = "알림-개발용")
@RestController
@RequestMapping("/api/dev/fcm")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.dev-tools.enabled", havingValue = "true")
public class DevFcmTestController {

    private final FcmService fcmService;

    @Operation(summary = "FCM 발송 테스트",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    [동작]
                    - token 지정 → 해당 디바이스 토큰으로 직접 발송 (DB 조회 없음)
                    - userId 지정 → 해당 사용자의 등록된 모든 FCM 토큰으로 발송
                    - 둘 다 지정 시 token 우선

                    [주의]
                    app.dev-tools.enabled=true 환경에서만 활성화. 프로덕션에서는 404.
                    """)
    @PostMapping("/test")
    public ResponseEntity<ApiResponse<Void>> test(@RequestBody FcmTestRequest request) {
        String title = request.getTitle() != null ? request.getTitle() : "테스트 알림";
        String body = request.getBody() != null ? request.getBody() : "FCM 발송 경로 확인용 메시지입니다.";

        if (request.getToken() != null && !request.getToken().isBlank()) {
            fcmService.sendToToken(request.getToken(), title, body, null);
        } else if (request.getUserId() != null && !request.getUserId().isBlank()) {
            fcmService.sendToUser(request.getUserId(), title, body, null);
        } else {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        return ResponseEntity.ok(ApiResponse.ok("FCM 발송 요청 완료 (서버 로그로 성공/실패 확인)"));
    }
}

package kr.silverbridge.main.domain.camera.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import kr.silverbridge.main.domain.camera.service.CameraStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 카메라 영상(MJPEG) 중계 - {@code <img src>}로 여는 경로.
 *
 * <p>{@code <img>}는 Authorization 헤더를 보낼 수 없어 SecurityConfig에서 이 GET 경로만 permitAll로 열고,
 * 인증은 1회용 스트림 티켓이 대신한다. 티켓은 보호자 인증 API({@code POST /api/guardian/camera/{sessionId}/stream-ticket})로만
 * 발급되고, 소비 시점에 계정 상태·ACTIVE 연결을 다시 확인한다({@link CameraStreamService#relay}).
 * 역할 게이트({@code @PreAuthorize})가 없는 이유가 이것이다 - 인증 주체가 없는 요청이라 역할 대신 티켓 주인으로 판정한다.</p>
 */
@Tag(name = "보호자 - 카메라")
@RestController
@RequiredArgsConstructor
public class CameraStreamController {

    private final CameraStreamService cameraStreamService;

    @Operation(summary = "실시간 영상 (MJPEG, 티켓 인증)",
            description = """
                    Authorization 헤더 없이 ticket 쿼리로 인증합니다(1회용, 60초).
                    <img src="{API}/api/camera/stream/{sessionId}/mjpeg?ticket={ticket}">
                    응답은 multipart/x-mixed-replace 스트림입니다. 최대 30분 뒤 서버가 끊으며, 시청 중 연결이 해제되거나
                    계정이 정지되면 1분 안에 끊깁니다. 끊기면 새 티켓으로 다시 여세요.
                    한 사람이 동시에 볼 수 있는 영상은 2개입니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "MJPEG 스트림"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "CAMERA_STREAM_TICKET_INVALID(없음·만료·재사용·다른 카메라용)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CAMERA_NOT_CONNECTED / INACTIVE_USER", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "CAMERA_NOT_FOUND / CAMERA_NOT_STREAMING", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "CAMERA_STREAM_LIMIT_EXCEEDED", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "CAMERA_STREAM_UNAVAILABLE", content = @Content)
    })
    @GetMapping("/api/camera/stream/{sessionId}/mjpeg")
    public void stream(@PathVariable String sessionId,
                       @RequestParam(required = false) String ticket,
                       HttpServletResponse response) throws IOException {
        cameraStreamService.relay(sessionId, ticket, response);
    }
}

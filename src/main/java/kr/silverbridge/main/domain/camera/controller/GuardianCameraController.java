package kr.silverbridge.main.domain.camera.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.camera.dto.CameraLiveStatusResponse;
import kr.silverbridge.main.domain.camera.dto.GuardianCameraView;
import kr.silverbridge.main.domain.camera.dto.GuardianLiveCameraView;
import kr.silverbridge.main.domain.camera.dto.StreamTicketResponse;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.domain.camera.service.CameraStreamService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 보호자용 이상감지 카메라 API. ACTIVE 연결된 피보호자들의 활성 카메라만 조회한다.
 * 클래스 레벨 {@code @PreAuthorize("hasRole('GUARDIAN')")}로 GUARDIAN만 접근 가능(WARD/ADMIN 403).
 */
@Tag(name = "보호자 - 카메라")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('GUARDIAN')")
public class GuardianCameraController {

    private final CameraService cameraService;
    private final CameraStreamService cameraStreamService;

    @Operation(summary = "연결된 피보호자 카메라 목록 조회 (allowlist)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    ACTIVE 연결된 피보호자들이 등록한 활성 카메라를 방별로 반환합니다.
                    연결되지 않은 피보호자의 카메라는 목록에 포함되지 않습니다(IDOR 차단).
                    연결이 없으면 빈 배열을 반환합니다.

                    [FE 사용법]
                    카드 표기는 wardName · label (예 "남궁명진 · 거실").
                    실시간 카메라 보기 화면은 송출 상태가 붙은 GET /api/guardian/camera/live를 쓰고,
                    영상·상태도 백엔드 API로 받습니다(AI 서버 직접 호출 금지, 2026-10-03).
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "연결된 피보호자들의 활성 카메라 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요", content = @Content)
    })
    @GetMapping("/api/guardian/camera")
    public ResponseEntity<ApiResponse<List<GuardianCameraView>>> getConnectedWardCameras(
            @AuthenticationPrincipal String guardianId) {
        return ResponseEntity.ok(ApiResponse.ok(cameraService.getConnectedWardCameras(guardianId)));
    }

    @Operation(summary = "실시간 카메라 목록 (방 이름 + 송출 상태)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    이상감지 > 실시간 카메라 보기 화면용. ACTIVE 연결된 피보호자의 등록 카메라에 AI 송출 상태를 붙여 반환합니다.
                    status: running(송출 중) / disconnected(10초 이상 프레임 없음) / offline(송출 안 함).
                    status가 null이면 "꺼짐"이 아니라 AI 서버 장애로 지금 확인할 수 없다는 뜻입니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "목록 반환(연결·카메라 없으면 빈 배열)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요", content = @Content)
    })
    @GetMapping("/api/guardian/camera/live")
    public ResponseEntity<ApiResponse<List<GuardianLiveCameraView>>> getLiveCameras(
            @AuthenticationPrincipal String guardianId) {
        return ResponseEntity.ok(ApiResponse.ok(cameraStreamService.getLiveCameras(guardianId)));
    }

    @Operation(summary = "카메라 송출 상태 + 최근 AI 분석",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    화면 첫 진입 때 현재 상태를 그리는 용도입니다. 이후 변화는 STOMP /topic/{userId}/camera-analysis로 옵니다.
                    송출하지 않는 카메라는 status=offline. analysis는 아직 받은 결과가 없으면 null입니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "상태 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요 / CAMERA_NOT_CONNECTED(연결된 피보호자의 카메라가 아님)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "CAMERA_NOT_FOUND(없거나 등록되지 않은 카메라)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "CAMERA_STREAM_UNAVAILABLE(AI 서버 응답 없음)", content = @Content)
    })
    @GetMapping("/api/guardian/camera/{sessionId}/status")
    public ResponseEntity<ApiResponse<CameraLiveStatusResponse>> getStatus(
            @AuthenticationPrincipal String guardianId, @PathVariable String sessionId) {
        return ResponseEntity.ok(ApiResponse.ok(cameraStreamService.getStatus(guardianId, sessionId)));
    }

    @Operation(summary = "최신 정지 화면 (JPEG)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    image/jpeg 바이트를 반환합니다. FE는 fetch → blob URL로 표시합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "JPEG"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요 / CAMERA_NOT_CONNECTED", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "CAMERA_NOT_FOUND / CAMERA_NOT_STREAMING(아직 프레임 없음)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "CAMERA_STREAM_UNAVAILABLE", content = @Content)
    })
    @GetMapping("/api/guardian/camera/{sessionId}/latest-frame")
    public ResponseEntity<byte[]> getLatestFrame(
            @AuthenticationPrincipal String guardianId, @PathVariable String sessionId) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore())
                .body(cameraStreamService.getLatestFrame(guardianId, sessionId));
    }

    @Operation(summary = "영상 1회용 티켓 발급",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    <img>는 Authorization 헤더를 보낼 수 없어, 이 티켓을 영상 주소에 붙입니다.
                    <img src="{API}/api/camera/stream/{sessionId}/mjpeg?ticket={ticket}">
                    60초 안에 1번만 쓸 수 있습니다. 영상을 다시 열 때마다 새로 발급하세요.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "티켓 발급"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요 / CAMERA_NOT_CONNECTED", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "CAMERA_NOT_FOUND", content = @Content)
    })
    @PostMapping("/api/guardian/camera/{sessionId}/stream-ticket")
    public ResponseEntity<ApiResponse<StreamTicketResponse>> issueStreamTicket(
            @AuthenticationPrincipal String guardianId, @PathVariable String sessionId) {
        return ResponseEntity.ok(ApiResponse.ok(cameraStreamService.issueTicket(guardianId, sessionId)));
    }
}

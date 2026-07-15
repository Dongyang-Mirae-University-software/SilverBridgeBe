package kr.silverbridge.main.domain.camera.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.camera.dto.GuardianCameraView;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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

    @Operation(summary = "연결된 피보호자 카메라 목록 조회 (allowlist)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    ACTIVE 연결된 피보호자들이 등록한 활성 카메라를 방별로 반환합니다.
                    연결되지 않은 피보호자의 카메라는 목록에 포함되지 않습니다(IDOR 차단).
                    연결이 없으면 빈 배열을 반환합니다.

                    [FE 사용법]
                    이상감지 화면은 이 응답의 sessionId 집합으로 AI 서버의 live-streams 목록과
                    WebSocket live_streams 브로드캐스트를 필터/구독합니다.
                    카드 표기는 wardName · label (예 "남궁명진 · 거실").
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
}

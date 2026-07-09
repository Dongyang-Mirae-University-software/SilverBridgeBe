package kr.silverbridge.main.domain.camera.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.camera.dto.CameraRegisterRequest;
import kr.silverbridge.main.domain.camera.dto.CameraResponse;
import kr.silverbridge.main.domain.camera.dto.CameraUpdateRequest;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 피보호자용 이상감지 카메라 API. 본인 카메라 등록·목록·수정·삭제.
 * 클래스 레벨 {@code @PreAuthorize("hasRole('WARD')")}로 WARD만 접근 가능(GUARDIAN/ADMIN 403).
 * 소유자는 항상 accessToken의 wardId — 요청 body의 사용자 ID는 신뢰하지 않는다.
 */
@Tag(name = "피보호자 - 카메라")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('WARD')")
public class WardCameraController {

    private final CameraService cameraService;

    @Operation(summary = "카메라 등록 (재등록 시 멱등)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    이 기기를 특정 방(거실/안방/방1~3 등)의 카메라로 등록합니다.
                    SessionID·DeviceID는 모두 서버가 발급하므로 사용자는 방 이름만 입력합니다.

                    [FE 사용법]
                    1. localStorage에 저장된 deviceId가 있으면 함께 전송 → 같은 기기로 인식되어 기존 SessionID 재사용(방 이름만 갱신)
                    2. 없으면 deviceId 생략 → 서버가 신규 SessionID·DeviceID 발급
                    3. 응답의 deviceId를 localStorage에 저장(덮어쓰기)
                    4. 응답의 sessionId·deviceId로 AI 송출 시작
                       (createStreamSession: sessionId=sessionId, cameraIdentifier=deviceId, fps=recommendedFps)
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "등록된(또는 기존) 카메라 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "방 이름 누락 또는 형식 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "피보호자 권한 필요", content = @Content)
    })
    @PostMapping("/api/ward/camera")
    public ResponseEntity<ApiResponse<CameraResponse>> register(
            @AuthenticationPrincipal String wardId,
            @Valid @RequestBody CameraRegisterRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(cameraService.register(wardId, request)));
    }

    @Operation(summary = "내 카메라 목록 조회",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    본인이 등록한 카메라를 방별로 최신순 반환합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "내 카메라 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "피보호자 권한 필요", content = @Content)
    })
    @GetMapping("/api/ward/camera")
    public ResponseEntity<ApiResponse<List<CameraResponse>>> getMyCameras(
            @AuthenticationPrincipal String wardId) {
        return ResponseEntity.ok(ApiResponse.ok(cameraService.getMyCameras(wardId)));
    }

    @Operation(summary = "카메라 수정 (방 이름 변경 / 사용 토글)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    전달한 필드만 갱신합니다(null 필드는 미변경).
                    타인 카메라 ID로 요청 시 404로 응답합니다(IDOR 차단).
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정된 카메라 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "피보호자 권한 필요", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않거나 본인 카메라가 아님", content = @Content)
    })
    @PatchMapping("/api/ward/camera/{id}")
    public ResponseEntity<ApiResponse<CameraResponse>> update(
            @AuthenticationPrincipal String wardId,
            @Parameter(description = "카메라 ID") @PathVariable Long id,
            @Valid @RequestBody CameraUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(cameraService.update(wardId, id, request)));
    }

    @Operation(summary = "카메라 삭제",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    본인 카메라를 삭제합니다. 타인 카메라 ID로 요청 시 404로 응답합니다(IDOR 차단).
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "피보호자 권한 필요", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않거나 본인 카메라가 아님", content = @Content)
    })
    @DeleteMapping("/api/ward/camera/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal String wardId,
            @Parameter(description = "카메라 ID") @PathVariable Long id) {
        cameraService.delete(wardId, id);
        return ResponseEntity.ok(ApiResponse.ok("카메라가 삭제되었습니다."));
    }
}

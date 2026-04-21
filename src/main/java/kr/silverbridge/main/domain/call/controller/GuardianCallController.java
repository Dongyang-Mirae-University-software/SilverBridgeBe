package kr.silverbridge.main.domain.call.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.call.dto.WebRtcSignalRequest;
import kr.silverbridge.main.domain.call.service.CallService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "보호자")
@RestController
@RequestMapping("/api/guardian/call")
@RequiredArgsConstructor
@PreAuthorize("hasRole('GUARDIAN')")
public class GuardianCallController {

    private final CallService callService;

    @Operation(summary = "WebRTC 시그널 전송 (answer / ice-candidate)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    SOS 통화 수락 시 WebRTC answer 또는 ICE candidate를 피보호자에게 전달합니다.
                    서버는 targetId의 /topic/{targetId}/webrtc-signal 으로 중계합니다.

                    [type 값]
                    - answer: 통화 수락 시
                    - ice-candidate: ICE 후보 교환 시
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "시그널 전송 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "targetId, type, data 필드 누락 또는 형식 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "보호자 권한 필요", content = @Content)
    })
    @PostMapping("/signal")
    public ResponseEntity<ApiResponse<Void>> sendSignal(
            @AuthenticationPrincipal String guardianId,
            @Valid @RequestBody WebRtcSignalRequest request) {
        callService.relaySignal(guardianId, request.getTargetId(), request.getType(), request.getData());
        return ResponseEntity.ok(ApiResponse.ok("시그널을 전송했습니다."));
    }

    @Operation(summary = "통화 종료",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    통화를 종료하고 피보호자에게 종료 알림을 전송합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "통화 종료 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "targetId 쿼리 파라미터 누락", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "보호자 권한 필요", content = @Content)
    })
    @PostMapping("/end")
    public ResponseEntity<ApiResponse<Void>> endCall(
            @AuthenticationPrincipal String guardianId,
            @RequestParam String targetId) {
        callService.endCall(guardianId, targetId);
        return ResponseEntity.ok(ApiResponse.ok("통화를 종료했습니다."));
    }
}

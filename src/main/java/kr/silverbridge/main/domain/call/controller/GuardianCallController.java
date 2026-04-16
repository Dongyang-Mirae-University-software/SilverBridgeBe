package kr.silverbridge.main.domain.call.controller;

import io.swagger.v3.oas.annotations.Operation;
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
                    """)
    @PostMapping("/end")
    public ResponseEntity<ApiResponse<Void>> endCall(
            @AuthenticationPrincipal String guardianId,
            @RequestParam String targetId) {
        callService.endCall(guardianId, targetId);
        return ResponseEntity.ok(ApiResponse.ok("통화를 종료했습니다."));
    }
}

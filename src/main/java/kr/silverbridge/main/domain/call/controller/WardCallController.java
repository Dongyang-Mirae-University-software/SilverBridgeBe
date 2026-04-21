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

@Tag(name = "피보호자")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('WARD')")
public class WardCallController {

    private final CallService callService;

    @Operation(summary = "SOS 긴급통화 발신",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    [SOS 긴급통화 흐름]
                    1. POST /api/ward/sos
                       → 연결된 전체 보호자에게 동시 FCM 알림 + WebSocket(/topic/{guardianId}/sos-call) 전송

                    2. 프론트가 priority=1 보호자에게 WebRTC offer 전송
                       POST /api/ward/call/signal  (type=offer, targetId=보호자ID)

                    3. 30초 내 응답 없으면 priority=2 보호자에게 재시도 (프론트 담당)

                    4. 보호자가 수락하면 WebRTC answer 교환 후 통화 연결
                       POST /api/guardian/call/signal (type=answer)

                    5. 통화 종료 시 POST /api/ward/call/end
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "전체 보호자에게 알림 전송 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "연결된 보호자 없음", content = @Content)
    })
    @PostMapping("/api/ward/sos")
    public ResponseEntity<ApiResponse<Void>> triggerSos(
            @AuthenticationPrincipal String wardId) {
        callService.triggerSos(wardId);
        return ResponseEntity.ok(ApiResponse.ok("긴급 통화 요청을 전송했습니다."));
    }

    @Operation(summary = "WebRTC 시그널 전송 (offer / ice-candidate)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    WebRTC 연결을 위한 SDP offer 또는 ICE candidate를 보호자에게 전달합니다.
                    서버는 targetId의 /topic/{targetId}/webrtc-signal 으로 중계합니다.

                    [type 값]
                    - offer: WebRTC 연결 시작 시
                    - ice-candidate: ICE 후보 교환 시
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "시그널 전송 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "targetId, type, data 필드 누락 또는 형식 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "인증 토큰 없음 또는 만료", content = @Content)
    })
    @PostMapping("/api/ward/call/signal")
    public ResponseEntity<ApiResponse<Void>> sendSignal(
            @AuthenticationPrincipal String wardId,
            @Valid @RequestBody WebRtcSignalRequest request) {
        callService.relaySignal(wardId, request.getTargetId(), request.getType(), request.getData());
        return ResponseEntity.ok(ApiResponse.ok("시그널을 전송했습니다."));
    }

    @Operation(summary = "통화 종료",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    통화를 종료하고 보호자에게 종료 알림을 전송합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "통화 종료 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "targetId 쿼리 파라미터 누락", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "인증 토큰 없음 또는 만료", content = @Content)
    })
    @PostMapping("/api/ward/call/end")
    public ResponseEntity<ApiResponse<Void>> endCall(
            @AuthenticationPrincipal String wardId,
            @RequestParam String targetId) {
        callService.endCall(wardId, targetId);
        return ResponseEntity.ok(ApiResponse.ok("통화를 종료했습니다."));
    }
}

package kr.silverbridge.main.domain.sos.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.sos.dto.SosResponse;
import kr.silverbridge.main.domain.sos.service.SosService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "피보호자")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('WARD')")
public class WardSosController {

    private final SosService sosService;

    @Operation(summary = "긴급 SOS 발생 (피보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    피보호자가 대시보드 > 긴급 전화 > "긴급 SOS" 버튼을 누르면 호출됩니다. 요청 바디는 없습니다.

                    [동작]
                    1. SOS 발생 이력(sos_events)을 저장합니다 — 알림 발송 성공 여부와 무관하게 항상 기록됩니다.
                    2. 연결된 ACTIVE 보호자 전원에게 긴급 알림을 발송합니다(커밋 후 비동기):
                       - WebSocket: /topic/{guardianId}/sos-triggered  → 보호자 웹 실시간 반응
                       - FCM 푸시:  "긴급 SOS" / "{피보호자}님이 긴급 도움을 요청했습니다."
                    긴급 알림은 필수 알림으로, 보호자의 알림 설정(ON/OFF)과 무관하게 항상 발송됩니다.

                    [응답] data.sosEventId(이력 ID), data.triggeredAt(발생 시각)

                    [범위 밖 — 프론트 처리]
                    - 119 통화 화면은 순수 프론트 연출이며 백엔드는 관여하지 않습니다.
                    - 보호자에게 직접 전화는 기존 보호자 조회(GET /api/ward/connection/active)의
                      전화번호 + tel: 링크로 처리합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "SOS 이력 저장 완료. data: sosEventId, triggeredAt (알림은 커밋 후 비동기 발송)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "피보호자(WARD)가 아닌 계정", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PostMapping("/api/ward/sos")
    public ResponseEntity<ApiResponse<SosResponse>> triggerSos(
            @AuthenticationPrincipal String wardId) {
        // SOS 이력(sos_events) 행을 생성하므로 201 Created.
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(sosService.trigger(wardId)));
    }
}

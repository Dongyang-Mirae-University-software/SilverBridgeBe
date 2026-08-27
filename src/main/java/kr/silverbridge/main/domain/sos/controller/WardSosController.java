package kr.silverbridge.main.domain.sos.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.sos.dto.SosResponse;
import kr.silverbridge.main.domain.sos.dto.SosTriggerRequest;
import kr.silverbridge.main.domain.sos.entity.SosTriggerType;
import kr.silverbridge.main.domain.sos.service.SosService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "피보호자 - SOS")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('WARD')")
public class WardSosController {

    private final SosService sosService;

    @Operation(summary = "긴급 SOS 발생 (피보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    피보호자가 대시보드 > 긴급 전화 > "긴급 SOS" 버튼을 누르면 호출됩니다.

                    [요청 바디 — 전체가 선택]
                    - location (선택, 100자): 발생 위치 자유 문구. 예 "자택 거실", "역삼동 인근"
                    - triggerType (선택): SOS_BUTTON(기본) / GUARDIAN_CALL
                    바디 없이 호출하면 위치 미상 · SOS_BUTTON 으로 기록됩니다(기존 호출 방식 그대로 동작).
                    서버는 위치를 추정하지 않습니다 — 프론트가 아는 값(거주지 라벨·브라우저 위치 등)을 그대로 보관하며,
                    보호자 SOS 이력 화면(GET /api/guardian/sos/history)의 location 으로 표시됩니다.

                    [발생 경로 - triggerType]
                    - SOS_BUTTON    : 피보호자가 "긴급 SOS" 버튼을 눌렀습니다.
                    - GUARDIAN_CALL : 피보호자가 SOS 화면에서 보호자를 골라 전화를 걸었습니다.
                                      보호자 카드를 눌러 전화를 거는 시점에 이 API도 함께 호출해 주세요 -
                                      호출하지 않으면 그 전화는 이력에 남지 않습니다.
                    두 경로 모두 보호자 알림은 동일하게 발송됩니다(전화받은 보호자 외 나머지도 상황을 알아야 하므로).

                    [동작]
                    1. SOS 발생 이력(sos_event)을 저장합니다 - 알림 발송 성공 여부와 무관하게 항상 기록됩니다.
                    2. 연결된 ACTIVE 보호자 전원에게 긴급 알림을 발송합니다(커밋 후 비동기):
                       - WebSocket: /topic/{guardianId}/sos-triggered  → 보호자 웹 실시간 반응
                       - FCM 푸시:  "긴급 SOS" / "{피보호자}님이 긴급 도움을 요청했습니다."
                    긴급 알림은 필수 알림으로, 보호자의 알림 설정(ON/OFF)과 무관하게 항상 발송됩니다.

                    [응답] data.sosEventId(이력 ID), data.triggeredAt(발생 시각)

                    [범위 밖 — 프론트 처리]
                    - 119 화면은 순수 프론트 연출입니다. 실제 전화는 걸지 않고 119가 입력된 키패드만 보여줍니다.
                    - 보호자에게 직접 전화는 기존 보호자 조회(GET /api/ward/connection/active)의
                      전화번호 + tel: 링크로 처리합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "SOS 이력 저장 완료. data: sosEventId, triggeredAt (알림은 커밋 후 비동기 발송)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "위치가 100자를 초과", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "피보호자(WARD)가 아닌 계정", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PostMapping("/api/ward/sos")
    public ResponseEntity<ApiResponse<SosResponse>> triggerSos(
            @AuthenticationPrincipal String wardId,
            // 바디 없는 기존 호출을 그대로 받기 위해 required=false — 긴급 경로라 바디 유무로 실패하게 두지 않는다.
            @Valid @RequestBody(required = false) SosTriggerRequest request) {
        String location = (request == null) ? null : request.location();
        SosTriggerType triggerType = (request == null) ? null : request.triggerType();
        // SOS 이력(sos_event) 행을 생성하므로 201 Created.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(sosService.trigger(wardId, location, triggerType)));
    }
}

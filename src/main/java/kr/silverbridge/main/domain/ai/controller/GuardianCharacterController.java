package kr.silverbridge.main.domain.ai.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.ai.service.AiEventService;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "보호자")
@RestController
@RequestMapping("/api/guardian")
@RequiredArgsConstructor
@PreAuthorize("hasRole('GUARDIAN')")
public class GuardianCharacterController {

    private final AiEventService aiEventService;
    private final ConnectionRepository connectionRepository;

    @Operation(summary = "피보호자 현재 표정 조회",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    연결된 피보호자의 마지막 AI 감지 표정을 조회합니다.
                    데이터가 없으면 NEUTRAL을 반환합니다.

                    실시간 표정은 WebSocket(/topic/{guardianId}/character-expression)을 구독하세요.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "표정 정보 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "연결되지 않은 피보호자", content = @Content)
    })
    @GetMapping("/character-expression/{wardId}")
    public ResponseEntity<ApiResponse<Map<String, String>>> getWardExpression(
            @AuthenticationPrincipal String guardianId,
            @PathVariable String wardId) {
        boolean connected = connectionRepository.existsByGuardianIdAndWardIdAndStatusNot(
                guardianId, wardId, ConnectionStatus.CANCELLED);
        if (!connected) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_AUTHORIZED);
        }

        String expression = aiEventService.getCurrentExpression(wardId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("expression", expression)));
    }
}

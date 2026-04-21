package kr.silverbridge.main.domain.ai.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.ai.service.AiEventService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "피보호자")
@RestController
@RequestMapping("/api/ward/character")
@RequiredArgsConstructor
@PreAuthorize("hasRole('WARD')")
public class WardCharacterController {

    private final AiEventService aiEventService;

    @Operation(summary = "현재 캐릭터 표정 조회",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    앱 시작 시 또는 재연결 시 마지막으로 AI가 감지한 표정을 조회합니다.
                    데이터가 없으면 NEUTRAL을 반환합니다.

                    실시간 표정은 WebSocket(/topic/{userId}/character-expression)을 구독하세요.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "expression 필드에 현재 표정 반환 (NEUTRAL / HAPPY / SAD / ANGRY / SURPRISED / FEARFUL / DISGUSTED)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "피보호자 권한 필요", content = @Content)
    })
    @GetMapping("/expression")
    public ResponseEntity<ApiResponse<Map<String, String>>> getCurrentExpression(
            @AuthenticationPrincipal String wardId) {
        String expression = aiEventService.getCurrentExpression(wardId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("expression", expression)));
    }
}

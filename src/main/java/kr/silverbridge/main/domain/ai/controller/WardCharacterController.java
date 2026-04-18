package kr.silverbridge.main.domain.ai.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.ai.service.AiEventService;
import kr.silverbridge.main.global.enums.CharacterExpression;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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
                    AI 서버가 10분 이상 데이터를 전송하지 않으면 NEUTRAL을 반환합니다.

                    실시간 표정은 WebSocket(/topic/{userId}/character-expression)을 구독하세요.
                    """)
    @GetMapping("/expression")
    public ResponseEntity<ApiResponse<Map<String, String>>> getCurrentExpression(
            @AuthenticationPrincipal String wardId) {
        CharacterExpression expression = aiEventService.getCurrentExpression(wardId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("expression", expression.name())));
    }
}

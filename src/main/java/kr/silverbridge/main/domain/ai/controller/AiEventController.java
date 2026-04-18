package kr.silverbridge.main.domain.ai.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.ai.dto.CharacterExpressionRequest;
import kr.silverbridge.main.domain.ai.service.AiEventService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "AI 서버 연동")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiEventController {

    private final AiEventService aiEventService;

    @Value("${ai.server.api-key}")
    private String aiServerApiKey;

    @Operation(summary = "캐릭터 표정 전달 (AI 서버 전용)",
            description = """
                    AI 서버가 카메라로 피보호자 표정을 분석한 결과를 전달합니다.

                    [인증]
                    헤더: X-AI-Server-Key: {api-key}
                    JWT 토큰 불필요 (AI 서버 전용 키 인증)

                    [처리 흐름]
                    1. AI 서버 → POST /api/ai/character-expression
                    2. 백엔드 → Redis에 현재 표정 저장 (10분 TTL)
                    3. 백엔드 → WebSocket(/topic/{wardId}/character-expression)으로 앱에 실시간 전달

                    [표정 값]
                    HAPPY, NEUTRAL, SAD, ANGRY, FEARFUL, SURPRISED
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "표정 처리 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "API 키 불일치")
    })
    @PostMapping("/character-expression")
    public ResponseEntity<ApiResponse<Void>> receiveCharacterExpression(
            @RequestHeader("X-AI-Server-Key") String apiKey,
            @Valid @RequestBody CharacterExpressionRequest request) {

        if (!aiServerApiKey.equals(apiKey)) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }

        aiEventService.handleCharacterExpression(request);
        return ResponseEntity.ok(ApiResponse.ok("표정 이벤트를 처리했습니다."));
    }
}

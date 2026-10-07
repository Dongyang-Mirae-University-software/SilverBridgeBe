package kr.silverbridge.main.domain.chat.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.chat.dto.ChatRelayRequest;
import kr.silverbridge.main.domain.chat.service.ChatRelayService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 보호자용 AI 의료 챗봇 중계 API(2026-10-07). FE → 백엔드 → AI.
 * 클래스 레벨 {@code @PreAuthorize("hasRole('GUARDIAN')")}로 GUARDIAN만 접근 가능(WARD/ADMIN 403).
 * 사용자 ID는 요청에서 받지 않고 토큰에서만 꺼낸다. 상담 내용은 민감 정보라 응답을 캐시하지 않는다.
 */
@Tag(name = "보호자 - AI 챗봇")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('GUARDIAN')")
public class GuardianChatController {

    private final ChatRelayService chatRelayService;

    @Operation(summary = "AI 챗봇에게 메시지 보내기",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    AI 의료 상담에 메시지를 보내고 답을 받습니다. 사용자는 로그인 토큰으로 정해지며 요청 본문에 userId를 넣지 않습니다
                    (넣어도 무시됩니다). 응답 data는 AI 응답의 data 그대로입니다(reply, riskLevel, intent, type, tool 등).

                    message 또는 uiSelection 중 하나는 필수이고 message는 2000자 이하, history는 24개 이하입니다.
                    모델이 답을 만드는 데 시간이 걸립니다. 서버는 최대 130초까지 기다리므로 FE 타임아웃은 그보다 길게(권장 140초) 잡으세요.

                    [오류 code]
                    CHAT_INVALID_REQUEST(400) / CHAT_UNAVAILABLE(503, AI 서버 불가·일시 중단) / CHAT_TIMEOUT(504) /
                    CHAT_LIMIT_EXCEEDED(429, 이전 전송이 아직 진행 중) / TOO_MANY_REQUESTS(429, 분·시간당 전송 한도, Retry-After 헤더)
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "AI 응답"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "CHAT_INVALID_REQUEST", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "CHAT_LIMIT_EXCEEDED / TOO_MANY_REQUESTS", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "CHAT_UNAVAILABLE", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "504", description = "CHAT_TIMEOUT", content = @Content)
    })
    @PostMapping("/api/guardian/chat")
    public ResponseEntity<ApiResponse<JsonNode>> send(@AuthenticationPrincipal String guardianId,
                                                      @Valid @RequestBody ChatRelayRequest request) {
        return noStore(chatRelayService.send(guardianId, request));
    }

    @Operation(summary = "내 상담 기록 목록",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    로그인한 보호자 본인의 상담 기록만 최신순으로 반환합니다(쿼리로 userId를 받지 않습니다).
                    data는 기록 배열이며, FE가 쓰는 필드(id·message·reply·engine·intent·type·tool·toolData·ui·riskLevel·createdAt 등)만 담깁니다(AI 내부 정보 제외). 기록이 없으면 빈 배열입니다.

                    [오류 code] CHAT_UNAVAILABLE(503) / CHAT_TIMEOUT(504) / TOO_MANY_REQUESTS(429)
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "기록 목록(없으면 빈 배열)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "CHAT_UNAVAILABLE", content = @Content)
    })
    @GetMapping("/api/guardian/chat/logs")
    public ResponseEntity<ApiResponse<JsonNode>> logs(@AuthenticationPrincipal String guardianId) {
        return noStore(chatRelayService.logs(guardianId));
    }

    @Operation(summary = "내 상담 기록 상세",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    본인 기록만 조회됩니다. 없는 기록과 남의 기록은 구분하지 않고 모두 404 CHAT_LOG_NOT_FOUND입니다. 응답 필드는 목록과 같습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "기록 상세"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "CHAT_LOG_NOT_FOUND", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "CHAT_UNAVAILABLE", content = @Content)
    })
    @GetMapping("/api/guardian/chat/logs/{chatId}")
    public ResponseEntity<ApiResponse<JsonNode>> logDetail(@AuthenticationPrincipal String guardianId,
                                                           @PathVariable long chatId) {
        return noStore(chatRelayService.logDetail(guardianId, chatId));
    }

    private static ResponseEntity<ApiResponse<JsonNode>> noStore(JsonNode data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}

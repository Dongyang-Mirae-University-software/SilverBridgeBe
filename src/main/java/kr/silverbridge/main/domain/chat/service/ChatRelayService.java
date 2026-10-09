package kr.silverbridge.main.domain.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.silverbridge.main.domain.chat.client.AiChatClient;
import kr.silverbridge.main.domain.chat.config.ChatRelayProperties;
import kr.silverbridge.main.domain.chat.dto.ChatRelayRequest;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.security.RateLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * AI 챗 중계(2026-10-07). 보호자·피보호자 컨트롤러가 함께 쓴다 - 역할 판단은 컨트롤러 몫이고 이 서비스는 역할을 모른다.
 * <b>AI에 넘기는 사용자 ID는 항상 호출자의 토큰 ID</b>다 - 요청 본문의 userId는 DTO에
 * 필드가 없어 받지 않고, 기록 조회도 토큰 ID로만 묻는다(남의 기록 조회 IDOR 차단).
 *
 * <p>상담 내용은 민감 정보라 메시지·응답 본문은 로그에 남기지 않는다(userId·길이·소요 시간까지만). 기록 응답은
 * {@link ChatLogProjection} 허용 목록으로 걸러 AI 내부 정보(contextJson·upstreamMeta 등)를 FE에 내리지 않는다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRelayService {

    private static final int MAX_CONTEXT_BYTES = 8 * 1024;
    private static final String SEND_ENDPOINT = "chat-send";
    private static final String LOGS_ENDPOINT = "chat-logs";
    private static final int LOGS_PER_MINUTE = 30;
    private static final int LOGS_PER_HOUR = 600;

    private final AiChatClient aiChatClient;
    private final ChatSlots slots;
    private final RateLimitService rateLimitService;
    private final ChatRelayProperties properties;
    private final ObjectMapper objectMapper;

    public Object send(String userId, ChatRelayRequest request) {
        if (!properties.isEnabled()) {
            throw new CustomException(ErrorCode.CHAT_UNAVAILABLE);
        }
        validate(request);
        byte[] body = buildBody(userId, request);

        rateLimitService.check(SEND_ENDPOINT, userId, properties.getPerMinute(), properties.getPerHour());
        long startedAt = System.nanoTime();
        try (ChatSlots.Slot ignored = slots.acquire(userId)) {
            JsonNode reply = aiChatClient.send(body);
            log.info("[CHAT-RELAY] 전송 완료: userId={}, elapsedMs={}", userId,
                    (System.nanoTime() - startedAt) / 1_000_000);
            return plain(reply);
        }
    }

    public Object logs(String userId) {
        rateLimitService.check(LOGS_ENDPOINT, userId, LOGS_PER_MINUTE, LOGS_PER_HOUR);
        JsonNode projected = ChatLogProjection.projectAll(aiChatClient.logs(userId));
        if (projected == null) {
            log.warn("[CHAT-RELAY] AI 기록 목록 형식 이상: call=logs");
            throw new CustomException(ErrorCode.CHAT_UNAVAILABLE);
        }
        return plain(projected);
    }

    public Object logDetail(String userId, long chatId) {
        rateLimitService.check(LOGS_ENDPOINT, userId, LOGS_PER_MINUTE, LOGS_PER_HOUR);
        JsonNode detail = aiChatClient.logDetail(userId, chatId).orElseThrow(() -> {
            // AI는 없는 기록과 남의 기록을 구분하지 않고 404로 답한다 - 둘 다 여기로 온다(내용 없이 id만 남긴다)
            log.info("[CHAT-LOG-NOT-FOUND] userId={}, chatId={}", userId, chatId);
            return new CustomException(ErrorCode.CHAT_LOG_NOT_FOUND);
        });
        JsonNode projected = ChatLogProjection.project(detail);
        if (projected == null) {
            log.warn("[CHAT-RELAY] AI 기록 상세 형식 이상: call=log-detail");
            throw new CustomException(ErrorCode.CHAT_UNAVAILABLE);
        }
        return plain(projected);
    }

    /**
     * 웹 응답으로 내보낼 값은 Jackson 2 노드가 아니라 일반 객체(Map·List·값)여야 한다.
     * Boot 4 웹 변환기는 Jackson 3라 Jackson 2 JsonNode 를 bean 으로 직렬화해 버린다(2026-10-08).
     */
    private Object plain(JsonNode node) {
        return objectMapper.convertValue(node, Object.class);
    }

    private void validate(ChatRelayRequest request) {
        boolean hasMessage = StringUtils.hasText(request.message());
        if (!hasMessage && request.uiSelection() == null) {
            throw new CustomException(ErrorCode.CHAT_INVALID_REQUEST);
        }
        if (hasMessage && request.message().length() > properties.getMaxMessageChars()) {
            throw new CustomException(ErrorCode.CHAT_INVALID_REQUEST);
        }
    }

    /** AI 요청 본문. {@code userId}는 인자로 받은 토큰 ID로 <b>마지막에 덮어쓴다</b>. */
    private byte[] buildBody(String userId, ChatRelayRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("message", request.message() == null ? "" : request.message());
        if (StringUtils.hasText(request.sessionId())) {
            body.put("sessionId", request.sessionId());
        }
        if (request.history() != null) {
            body.set("history", objectMapper.valueToTree(request.history()));
        }
        if (request.context() != null && !request.context().isEmpty()) {
            JsonNode context = objectMapper.valueToTree(request.context());
            if (context.toString().getBytes(StandardCharsets.UTF_8).length > MAX_CONTEXT_BYTES) {
                throw new CustomException(ErrorCode.CHAT_INVALID_REQUEST);
            }
            body.set("context", context);
        }
        if (request.uiSelection() != null) {
            body.set("uiSelection", objectMapper.valueToTree(request.uiSelection()));
        }
        body.put("userId", userId);
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.CHAT_INVALID_REQUEST);
        }
    }
}

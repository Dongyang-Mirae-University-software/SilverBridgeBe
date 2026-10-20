package kr.silverbridge.main.domain.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.silverbridge.main.domain.chat.client.AiChatClient;
import kr.silverbridge.main.domain.chat.config.ChatRelayProperties;
import kr.silverbridge.main.domain.chat.dto.ChatRelayRequest;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.security.RateLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 챗 중계(2026-10-07). 보호자·피보호자 컨트롤러가 함께 쓴다 - 역할 판단은 컨트롤러 몫이고 이 서비스는 역할을 모른다.
 * <b>AI에 넘기는 사용자 ID는 항상 호출자의 토큰 ID</b>다 - 요청 본문의 userId는 DTO에
 * 필드가 없어 받지 않고, 기록 조회도 토큰 ID로만 묻는다(남의 기록 조회 IDOR 차단).
 *
 * <p><b>대리 상담(보호자가 피보호자 대신, 2026-10-20)</b>: AI는 {@code context.wardId}로 예약 계정을 만들어 그 계정으로 예약을
 * 조회·생성한다. 이 값을 클라이언트가 정하면 남의 예약을 보거나 남의 이름으로 예약할 수 있으므로, <b>wardId·wardName·wardPhone은
 * 항상 BE가 정한다</b> - 보호자 경로는 ACTIVE 연결({@code isActiveConnection}, {@code getMyWards} 금지)을 확인한 뒤 DB 값으로
 * 채우고, 그 밖의 경로는 이 세 키를 지운다.</p>
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
    private static final java.util.Set<String> WARD_KEYS = java.util.Set.of("wardId", "wardName", "wardPhone");

    private final AiChatClient aiChatClient;
    private final ChatSlots slots;
    private final RateLimitService rateLimitService;
    private final ChatRelayProperties properties;
    private final ObjectMapper objectMapper;
    private final ConnectionService connectionService;
    private final UserRepository userRepository;

    /** 본인 상담(피보호자 경로 등). context 의 대리 상담 키(wardId·wardName·wardPhone)는 버린다. */
    public Object send(String userId, ChatRelayRequest request) {
        return relay(userId, request, null);
    }

    /** 보호자 경로. context.wardId 가 있으면 ACTIVE 연결을 확인하고 이름·전화는 DB 값으로 채운다(없으면 본인 상담). */
    public Object sendAsGuardian(String guardianId, ChatRelayRequest request) {
        if (!properties.isEnabled()) {
            throw new CustomException(ErrorCode.CHAT_UNAVAILABLE);
        }
        validate(request);
        return relay(guardianId, request, resolveWard(guardianId, request.context()));
    }

    private Object relay(String userId, ChatRelayRequest request, User ward) {
        if (!properties.isEnabled()) {
            throw new CustomException(ErrorCode.CHAT_UNAVAILABLE);
        }
        validate(request);
        byte[] body = buildBody(userId, request, ward);

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

    /** 대리 상담 키를 모두 지우고, 검증된 피보호자가 있을 때만 DB 값으로 다시 채운다(클라이언트 값은 어느 것도 통과하지 못한다). */
    private static Map<String, Object> sanitizedContext(Map<String, Object> raw, User ward) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (raw != null) {
            context.putAll(raw);
        }
        context.keySet().removeAll(WARD_KEYS);
        if (ward != null) {
            context.put("wardId", ward.getId());
            context.put("wardName", ward.getName());
            if (StringUtils.hasText(ward.getPhone())) {
                context.put("wardPhone", ward.getPhone());
            }
        }
        return context;
    }

    /** 요청 context 의 wardId 를 ACTIVE 연결로 검증해 피보호자 행을 돌려준다. wardId 가 없으면 null(본인 상담). */
    private User resolveWard(String guardianId, Map<String, Object> context) {
        Object raw = context == null ? null : context.get("wardId");
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String text)) {
            throw new CustomException(ErrorCode.CHAT_INVALID_REQUEST);
        }
        String wardId = text.trim();
        if (wardId.isEmpty()) {
            return null;
        }
        if (!connectionService.isActiveConnection(guardianId, wardId)) {
            log.warn("[IDOR-ATTEMPT] 연결되지 않은 피보호자 대리 상담 시도: guardianId={}, wardId={}", guardianId, wardId);
            throw new CustomException(ErrorCode.CHAT_WARD_NOT_CONNECTED);
        }
        return userRepository.findById(wardId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_WARD_NOT_CONNECTED));
    }

    /** AI 요청 본문. {@code userId}는 인자로 받은 토큰 ID로 <b>마지막에 덮어쓴다</b>. */
    private byte[] buildBody(String userId, ChatRelayRequest request, User ward) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("message", request.message() == null ? "" : request.message());
        if (StringUtils.hasText(request.sessionId())) {
            body.put("sessionId", request.sessionId());
        }
        if (request.history() != null) {
            body.set("history", objectMapper.valueToTree(request.history()));
        }
        Map<String, Object> contextMap = sanitizedContext(request.context(), ward);
        if (!contextMap.isEmpty()) {
            JsonNode context = objectMapper.valueToTree(contextMap);
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

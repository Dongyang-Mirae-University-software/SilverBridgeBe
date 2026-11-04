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
import kr.silverbridge.main.global.enums.Role;
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
 * AI 챗 중계(2026-10-07). 보호자·피보호자 컨트롤러가 함께 쓴다 - 역할 인가는 컨트롤러 몫이다. 이 서비스는 호출 경로
 * ({@link #send} = 피보호자, {@link #sendAsGuardian} = 보호자)로 {@code context.role}만 정한다(2026-10-28, 아래).
 * <b>AI에 넘기는 사용자 ID는 항상 호출자의 토큰 ID</b>다 - 요청 본문의 userId는 DTO에
 * 필드가 없어 받지 않고, 기록 조회도 토큰 ID로만 묻는다(남의 기록 조회 IDOR 차단).
 *
 * <p><b>대리 상담(보호자가 피보호자 대신, 2026-10-20)</b>: AI는 {@code context.wardId}로 예약 계정을 만들어 그 계정으로 예약을
 * 조회·생성한다. 이 값을 클라이언트가 정하면 남의 예약을 보거나 남의 이름으로 예약할 수 있으므로, <b>wardId·wardName·wardPhone은
 * 항상 BE가 정한다</b> - 보호자 경로는 ACTIVE 연결({@code isActiveConnection}, {@code getMyWards} 금지)을 확인한 뒤 DB 값으로
 * 채우고, 그 밖의 경로는 이 세 키를 지운다. 환자 {@code name}·{@code phone}도 클라이언트 값을 버리고 대리 상담은 피보호자, 본인 상담은
 * 로그인한 본인의 DB 값으로 채운다(2026-11-04).</p>
 *
 * <p><b>context.role은 BE가 정한다(2026-10-28)</b>: AI 관리자 통계(챗 집계)가 역할별 상담 수를 이 값으로 센다. 클라이언트 값을
 * 그대로 넘기면 빠지거나(옛 FE는 비어 있었다) 위조된 값이 통계에 섞이므로 경로의 역할로 덮어쓴다. 권한 판단에는 여전히 쓰지 않는다.</p>
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
    /** 환자 이름·전화. AI 는 wardId 가 없으면 이 둘을 예약 환자·예약 계정 정보로 쓴다 - 클라이언트 값은 항상 버리고 BE가 DB 값으로 채운다(2026-11-04). */
    private static final java.util.Set<String> IDENTITY_KEYS = java.util.Set.of("name", "phone");
    /**
     * 환자 지역. AI 예약 흐름이 지역 선택지(같은 시·도 지역 먼저)와 병원 검색에 쓴다(2026-11-04, AI #171 점검 M-2).
     * 클라이언트 값은 버리고 환자(대리 상담은 피보호자, 본인 상담은 본인)의 users.address 를 시·군·구까지만 줄여 location 에 넣는다 -
     * 도로명·번지·상세주소·우편번호는 AI(챗 기록 userContext 포함)로 보내지 않는다.
     */
    private static final java.util.Set<String> LOCATION_KEYS = java.util.Set.of("location", "address", "addressDetail", "postcode");
    private static final java.util.Set<String> SIDO_SHORT = java.util.Set.of(
            "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "경기", "강원",
            "충북", "충남", "전북", "전남", "경북", "경남", "제주");
    private static final java.util.regex.Pattern REGION_UNIT =
            java.util.regex.Pattern.compile("[가-힣]+(특별시|광역시|특별자치시|특별자치도|도|시|군|구)");
    /** 사용자 ID 형식(UserIdGenerator: 영숫자, users.id VARCHAR(6)). */
    private static final java.util.regex.Pattern USER_ID_FORMAT = java.util.regex.Pattern.compile("\\A[A-Za-z0-9]{1,6}\\z");

    private final AiChatClient aiChatClient;
    private final ChatSlots slots;
    private final RateLimitService rateLimitService;
    private final ChatRelayProperties properties;
    private final ObjectMapper objectMapper;
    private final ConnectionService connectionService;
    private final UserRepository userRepository;

    /** 본인 상담(피보호자 경로). context 의 대리 상담 키는 버리고, name·phone 은 로그인한 본인의 DB 값으로 채운다. */
    public Object send(String userId, ChatRelayRequest request) {
        admit(userId, request);
        return relay(userId, request, null, findSelf(userId), Role.WARD);
    }

    /**
     * 보호자 경로. context.wardId 가 있으면 ACTIVE 연결을 확인하고 이름·전화는 DB 값으로 채운다(없으면 본인 상담).
     * 킬 스위치·본문 검증·속도 제한을 <b>wardId 확인보다 먼저</b> 해서, 위조 wardId 로 DB 조회·경고 로그를 무한히 만들지 못하게 한다.
     */
    public Object sendAsGuardian(String guardianId, ChatRelayRequest request) {
        admit(guardianId, request);
        User ward = resolveWard(guardianId, request.context());
        // 피보호자를 고르지 않은 본인 상담이면 보호자 본인의 DB 이름·전화를 쓴다(2026-11-04 사용자 결정 A)
        User self = ward == null ? findSelf(guardianId) : null;
        Object reply = relay(guardianId, request, ward, self, Role.GUARDIAN);
        // AI 호출은 최대 130초라 그 사이 연결이 끊길 수 있다. 끊겼다면 응답(피보호자의 예약 내용이 담길 수 있음)을 보호자에게 주지 않는다.
        if (ward != null && !connectionService.isActiveConnection(guardianId, ward.getId())) {
            log.warn("[IDOR-ATTEMPT] 대리 상담 중 연결 해제 감지, 응답 폐기: guardianId={}, wardId={}", guardianId, ward.getId());
            throw new CustomException(ErrorCode.CHAT_WARD_NOT_CONNECTED);
        }
        return reply;
    }

    /** 전송 공통 입장 검사: 킬 스위치 -> 본문 검증 -> 속도 제한. */
    private void admit(String userId, ChatRelayRequest request) {
        if (!properties.isEnabled()) {
            throw new CustomException(ErrorCode.CHAT_UNAVAILABLE);
        }
        validate(request);
        rateLimitService.check(SEND_ENDPOINT, userId, properties.getPerMinute(), properties.getPerHour());
    }

    private Object relay(String userId, ChatRelayRequest request, User ward, User self, Role role) {
        byte[] body = buildBody(userId, request, ward, self, role);
        long startedAt = System.nanoTime();
        try (ChatSlots.Slot ignored = slots.acquire(userId)) {
            JsonNode reply = aiChatClient.send(body);
            log.info("[CHAT-RELAY] 전송 완료: userId={}, elapsedMs={}", userId,
                    (System.nanoTime() - startedAt) / 1_000_000);
            SuggestedQuestions.sanitize(reply); // FE가 버튼으로 그리는 칸 - 형식만 보장(2026-10-30)
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

    /**
     * 대리 상담 키와 name·phone·지역 키를 모두 지우고 DB 값으로만 다시 채운다(클라이언트 값은 어느 것도 통과하지 못한다).
     * 대리 상담이면 검증된 피보호자, 본인 상담이면 로그인한 본인(self)의 값이다. 전화가 없으면 phone 키를 두지 않는다.
     * role 은 호출 경로의 역할로 덮어쓴다(AI 챗 집계의 역할별 수 근거).
     */
    private static Map<String, Object> sanitizedContext(Map<String, Object> raw, User ward, User self, Role role) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (raw != null) {
            context.putAll(raw);
        }
        context.keySet().removeAll(WARD_KEYS);
        context.keySet().removeAll(IDENTITY_KEYS);
        context.keySet().removeAll(LOCATION_KEYS);
        context.put("role", role.name());
        if (ward != null) {
            context.put("wardId", ward.getId());
            context.put("wardName", ward.getName());
            if (StringUtils.hasText(ward.getPhone())) {
                context.put("wardPhone", ward.getPhone());
            }
        }
        // AI 는 wardName/wardPhone 이 없으면 name/phone 을 환자 정보로 쓴다 - 대리 상담도 같은 값으로 함께 정한다
        User patient = ward != null ? ward : self;
        if (patient != null) {
            if (StringUtils.hasText(patient.getName())) {
                context.put("name", patient.getName());
            }
            if (StringUtils.hasText(patient.getPhone())) {
                context.put("phone", patient.getPhone());
            }
            String region = coarseRegion(patient.getAddress());
            if (region != null) {
                context.put("location", region);
            }
        }
        return context;
    }

    /**
     * 주소에서 시·도와 시·군·구까지만 남긴다("서울특별시 강남구 테헤란로 1" -> "서울특별시 강남구", "경기 성남시 분당구 …" -> 세 칸).
     * 동·도로명·번지는 버리고, 알아볼 수 없으면 null(보내지 않는다). AI coarse_region 과 같은 규칙이다.
     */
    static String coarseRegion(String address) {
        if (!StringUtils.hasText(address)) {
            return null;
        }
        String[] tokens = address.trim().split("\\s+");
        java.util.List<String> kept = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(3, tokens.length); i++) {
            String token = tokens[i];
            boolean unit = SIDO_SHORT.contains(token) || REGION_UNIT.matcher(token).matches();
            // 셋째 칸은 "OO시 OO구"(일반구)일 때만 - 도로명 낱말이 붙지 않게
            if (!unit || (i == 2 && !(kept.get(1).endsWith("시") && token.endsWith("구")))) {
                break;
            }
            kept.add(token);
        }
        return kept.isEmpty() ? null : String.join(" ", kept);
    }

    /** 본인 상담의 환자 정보용 본인 행. 없으면(탈퇴 직후 경합 등) null - 이름·전화 없이 진행한다(로그에 값을 남기지 않는다). */
    private User findSelf(String userId) {
        return userRepository.findById(userId).orElse(null);
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
        // 형식이 틀리면 DB 조회·원문 로그 없이 거절한다(클라이언트 문자열을 로그에 남기면 줄바꿈 삽입·8KB 폭주가 가능하다)
        if (!USER_ID_FORMAT.matcher(wardId).matches()) {
            log.warn("[IDOR-ATTEMPT] 형식이 맞지 않는 피보호자 ID로 대리 상담 시도: guardianId={}, wardIdLength={}",
                    guardianId, wardId.length());
            throw new CustomException(ErrorCode.CHAT_WARD_NOT_CONNECTED);
        }
        if (!connectionService.isActiveConnection(guardianId, wardId)) {
            log.warn("[IDOR-ATTEMPT] 연결되지 않은 피보호자 대리 상담 시도: guardianId={}, wardId={}", guardianId, wardId);
            throw new CustomException(ErrorCode.CHAT_WARD_NOT_CONNECTED);
        }
        return userRepository.findById(wardId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_WARD_NOT_CONNECTED));
    }

    /** AI 요청 본문. {@code userId}는 인자로 받은 토큰 ID로 <b>마지막에 덮어쓴다</b>. */
    private byte[] buildBody(String userId, ChatRelayRequest request, User ward, User self, Role role) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("message", request.message() == null ? "" : request.message());
        if (StringUtils.hasText(request.sessionId())) {
            body.put("sessionId", request.sessionId());
        }
        // history 는 AI로 보내지 않는다(2026-10-29) - AI가 대화 이력을 서버 기록으로 만들고 요청의 history 를 무시한다(AI #91 S-7).
        // 요청 DTO 는 옛 FE 호환으로 history 를 계속 받아 검사만 한다.
        Map<String, Object> contextMap = sanitizedContext(request.context(), ward, self, role);
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

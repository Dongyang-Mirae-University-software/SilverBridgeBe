package kr.silverbridge.main.domain.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.silverbridge.main.domain.chat.client.AiChatClient;
import kr.silverbridge.main.domain.chat.config.ChatRelayProperties;
import kr.silverbridge.main.domain.chat.dto.ChatRelayRequest;
import kr.silverbridge.main.domain.connection.dto.ActiveWardSummary;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.security.RateLimitService;
import kr.silverbridge.main.global.util.UserIdFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
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
 * 채우고, 그 밖의 경로는 이 세 키를 지운다. 환자 프로필(이름·전화·생년월일·나이·성별·주소)도 클라이언트 값을 버리고 대리 상담은 피보호자,
 * 본인 상담은 로그인한 본인의 DB 값으로 채운다(2026-11-04, {@link #PROFILE_KEYS}).</p>
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
    /**
     * 보호자의 ACTIVE 피보호자 이름·관계 목록(2026-11-11). AI 가 예약을 시작할 때 "누구 예약인가요?"를 묻는 데 쓴다.
     * 항상 BE 가 DB 로 만든다 - 클라이언트 값은 모든 경로에서 버리고, 피보호자 경로에는 싣지 않는다. 전화·주소·생년월일 금지.
     */
    static final String GUARDIAN_WARDS_KEY = "guardianWards";
    /** 한 요청에 싣는 피보호자 수 상한(최신 연결순). context 8KB 안에 넉넉히 들어가는 값. */
    static final int MAX_GUARDIAN_WARDS = 20;
    /** AI 의 예약 대상 선택지 칸(AI·FE 계약, 2026-11-11). 값은 {@link #SELF_CHOICE} 또는 피보호자 ID. */
    static final String PATIENT_CHOICE_FIELD = "reservation_patient";
    static final String SELF_CHOICE = "__self__";
    /**
     * 환자 프로필 키. 클라이언트 값은 항상 버리고, 이 중 name·phone·birthDate·age·gender·address 만 BE가 환자(대리 상담이면 피보호자,
     * 아니면 본인)의 DB 값으로 다시 채운다(2026-11-04). AI 는 이 값을 예약 초안·예약 계정(이름·전화·생년월일), MedGemma(나이·성별),
     * 병원 검색 지역(address - location 이 없을 때, 시·구까지)에 쓴다. email·addressDetail·postcode·relation·patient* 는 AI 가 쓰지 않아 보내지 않는다.
     * location(사용자가 고른 검색 지역)은 클라이언트 값 그대로다.
     */
    private static final java.util.Set<String> PROFILE_KEYS = java.util.Set.of(
            "name", "phone", "age", "birthDate", "gender", "email", "address", "addressDetail", "postcode", "relation",
            "patientId", "patientName", "patientBirthDate", "patientPhone", "patientGender");
    private static final java.time.ZoneId KST = java.time.ZoneId.of("Asia/Seoul");
    /** 시·도(약칭·정식). 세종은 시·군·구가 없다. */
    private static final java.util.regex.Pattern SIDO = java.util.regex.Pattern.compile(
            "(서울|부산|대구|인천|광주|대전|울산|경기|강원|충북|충남|충청북도|충청남도|전북|전남|전라북도|전라남도|경북|경남|경상북도|경상남도|제주)"
                    + "(특별시|광역시|특별자치도|도)?");
    private static final java.util.regex.Pattern SEJONG = java.util.regex.Pattern.compile("세종(특별자치시|시)?");
    private static final java.util.regex.Pattern SIGUNGU = java.util.regex.Pattern.compile("\\S+[시군구]");
    /** 사용자 ID 형식(UserIdGenerator: 영숫자, users.id VARCHAR(6)). */

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
        // 피보호자는 대신 예약할 사람이 없다 - 예약 대상 선택지로 남의 ID 를 고르면 거절한다(보호자 경로와 같은 403)
        String chosen = patientChoice(request);
        if (chosen != null) {
            log.warn("[IDOR-ATTEMPT] 피보호자 경로에서 예약 대상 선택 시도: userId={}, valueLength={}", userId, chosen.length());
            throw new CustomException(ErrorCode.CHAT_WARD_NOT_CONNECTED);
        }
        return relay(userId, request, null, findSelf(userId), Role.WARD, null);
    }

    /**
     * 보호자 경로. context.wardId 가 있으면 ACTIVE 연결을 확인하고 이름·전화는 DB 값으로 채운다(없으면 본인 상담).
     * 킬 스위치·본문 검증·속도 제한을 <b>wardId 확인보다 먼저</b> 해서, 위조 wardId 로 DB 조회·경고 로그를 무한히 만들지 못하게 한다.
     */
    public Object sendAsGuardian(String guardianId, ChatRelayRequest request) {
        admit(guardianId, request);
        User ward = resolveWard(guardianId, request.context());
        String chosenWardId = verifyPatientChoice(guardianId, request, ward);
        // 피보호자를 고르지 않은 본인 상담이면 보호자 본인의 DB 이름·전화를 쓴다(2026-11-04 사용자 결정 A)
        User self = ward == null ? findSelf(guardianId) : null;
        List<ActiveWardSummary> guardianWards = connectionService.getActiveWardSummaries(guardianId, MAX_GUARDIAN_WARDS);
        Object reply = relay(guardianId, request, ward, self, Role.GUARDIAN, guardianWards);
        // AI 호출은 최대 130초라 그 사이 연결이 끊길 수 있다. 끊겼다면 응답(피보호자의 예약 내용이 담길 수 있음)을 보호자에게 주지 않는다.
        String proxyWardId = ward != null ? ward.getId() : chosenWardId;
        if (proxyWardId != null && !connectionService.isActiveConnection(guardianId, proxyWardId)) {
            log.warn("[IDOR-ATTEMPT] 대리 상담 중 연결 해제 감지, 응답 폐기: guardianId={}, wardId={}", guardianId, proxyWardId);
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

    private Object relay(String userId, ChatRelayRequest request, User ward, User self, Role role,
                         List<ActiveWardSummary> guardianWards) {
        byte[] body = buildBody(userId, request, ward, self, role, guardianWards);
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
     * 대리 상담 키와 환자 프로필 키를 모두 지우고 DB 값으로만 다시 채운다(클라이언트 값은 어느 것도 통과하지 못한다).
     * 대리 상담이면 검증된 피보호자, 본인 상담이면 로그인한 본인(self)의 값이다. DB 에 값이 없는 칸은 키를 두지 않는다.
     * role 은 호출 경로의 역할로 덮어쓴다(AI 챗 집계의 역할별 수 근거).
     */
    private static Map<String, Object> sanitizedContext(Map<String, Object> raw, User ward, User self, Role role,
                                                        List<ActiveWardSummary> guardianWards) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (raw != null) {
            context.putAll(raw);
        }
        context.keySet().removeAll(WARD_KEYS);
        context.keySet().removeAll(PROFILE_KEYS);
        context.remove(GUARDIAN_WARDS_KEY);
        context.put("role", role.name());
        if (guardianWards != null) {
            // 이름·관계만. 관계가 없으면(관리자 강제 연결 등) null - 키는 항상 세 개다(AI·FE 계약)
            List<Map<String, Object>> wards = new java.util.ArrayList<>();
            for (ActiveWardSummary summary : guardianWards) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("wardId", summary.wardId());
                item.put("name", summary.name());
                item.put("relation", summary.relation());
                wards.add(item);
            }
            context.put(GUARDIAN_WARDS_KEY, wards);
        }
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
            putProfile(context, patient, java.time.LocalDate.now(KST));
        }
        return context;
    }

    /** 환자 DB 값으로 프로필 키를 채운다. 값이 없는 칸은 키를 두지 않는다. 나이는 KST 오늘 기준 만 나이. */
    /**
     * AI 가 병원 검색에 쓸 수 있는 주소인지. AI 는 location 이 없으면 주소 <b>원문</b>으로 검색해서, 자리표시 값({@code <주소>})·
     * 외국 주소·낙서가 가면 검색이 0건이 되고 지역 선택지 대신 "찾지 못했습니다"가 나온다(2026-11-04 실측).
     * "시·도 + 시·군·구"(세종은 시·도만)로 시작할 때만 보내고, 아니면 빼서 AI 가 지역을 묻게 한다.
     */
    static boolean isSearchableAddress(String address) {
        if (!StringUtils.hasText(address)) {
            return false;
        }
        String[] tokens = address.strip().split("\\s+");
        if (SEJONG.matcher(tokens[0]).matches()) {
            return true;
        }
        return tokens.length >= 2 && SIDO.matcher(tokens[0]).matches() && SIGUNGU.matcher(tokens[1]).matches();
    }

    static void putProfile(Map<String, Object> context, User patient, java.time.LocalDate today) {
        if (StringUtils.hasText(patient.getName())) {
            context.put("name", patient.getName());
        }
        if (StringUtils.hasText(patient.getPhone())) {
            context.put("phone", patient.getPhone());
        }
        if (patient.getBirthDate() != null && !patient.getBirthDate().isAfter(today)) {
            context.put("birthDate", patient.getBirthDate().toString());
            context.put("age", java.time.Period.between(patient.getBirthDate(), today).getYears());
        }
        if (patient.getGender() != null) {
            context.put("gender", patient.getGender().name());
        }
        if (isSearchableAddress(patient.getAddress())) {
            context.put("address", patient.getAddress().strip());
        }
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
        if (!UserIdFormat.isWellFormed(wardId)) {
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

    /**
     * 예약 대상 선택지({@link #PATIENT_CHOICE_FIELD})로 고른 피보호자 ID. "나"({@link #SELF_CHOICE})·다른 칸·선택 없음이면 null.
     */
    private static String patientChoice(ChatRelayRequest request) {
        ChatRelayRequest.UiSelection selection = request.uiSelection();
        if (selection == null || !PATIENT_CHOICE_FIELD.equals(selection.field())) {
            return null;
        }
        String value = selection.value() == null ? "" : selection.value().trim();
        return SELF_CHOICE.equals(value) ? null : value;
    }

    /**
     * 예약 대상 선택지로 고른 피보호자를 context.wardId 와 같은 기준(형식 -> ACTIVE 연결)으로 검증한다(2026-11-11).
     * AI 가 선택 값을 그대로 환자로 묶을 수 있으므로, 남의 ID 를 고르면 AI 를 부르기 전에 403 이다.
     * 같은 턴에 검증된 context.wardId 가 있으면 두 값이 같아야 한다(다르면 어느 쪽이 환자인지 모호하다 - 400).
     *
     * @return 검증된 피보호자 ID, 선택이 없거나 "나"면 null
     */
    private String verifyPatientChoice(String guardianId, ChatRelayRequest request, User ward) {
        String chosen = patientChoice(request);
        if (chosen == null) {
            return null;
        }
        if (!UserIdFormat.isWellFormed(chosen)) {
            log.warn("[IDOR-ATTEMPT] 형식이 맞지 않는 예약 대상 선택: guardianId={}, valueLength={}", guardianId, chosen.length());
            throw new CustomException(ErrorCode.CHAT_WARD_NOT_CONNECTED);
        }
        if (ward != null) {
            if (!ward.getId().equals(chosen)) {
                throw new CustomException(ErrorCode.CHAT_INVALID_REQUEST);
            }
            return chosen; // resolveWard 가 이미 ACTIVE 를 확인했다
        }
        if (!connectionService.isActiveConnection(guardianId, chosen)) {
            log.warn("[IDOR-ATTEMPT] 연결되지 않은 피보호자를 예약 대상으로 선택: guardianId={}, wardId={}", guardianId, chosen);
            throw new CustomException(ErrorCode.CHAT_WARD_NOT_CONNECTED);
        }
        return chosen;
    }

    /** AI 요청 본문. {@code userId}는 인자로 받은 토큰 ID로 <b>마지막에 덮어쓴다</b>. */
    private byte[] buildBody(String userId, ChatRelayRequest request, User ward, User self, Role role,
                             List<ActiveWardSummary> guardianWards) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("message", request.message() == null ? "" : request.message());
        if (StringUtils.hasText(request.sessionId())) {
            body.put("sessionId", request.sessionId());
        }
        // history 는 AI로 보내지 않는다(2026-10-29) - AI가 대화 이력을 서버 기록으로 만들고 요청의 history 를 무시한다(AI #91 S-7).
        // 요청 DTO 는 옛 FE 호환으로 history 를 계속 받아 검사만 한다.
        Map<String, Object> contextMap = sanitizedContext(request.context(), ward, self, role, guardianWards);
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

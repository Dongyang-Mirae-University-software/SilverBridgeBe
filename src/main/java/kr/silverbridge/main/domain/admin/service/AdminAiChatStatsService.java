package kr.silverbridge.main.domain.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import kr.silverbridge.main.domain.admin.dto.AdminAiChatStatsResponse;
import kr.silverbridge.main.domain.admin.dto.AdminAiChatStatsResponse.ByRole;
import kr.silverbridge.main.domain.admin.dto.AdminAiChatStatsResponse.ByRoute;
import kr.silverbridge.main.domain.admin.dto.AdminAiChatStatsResponse.Daily;
import kr.silverbridge.main.domain.chat.client.AiChatClient;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.security.RateLimitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * 관리자 AI 통계 - 의료 챗봇 집계 중계(2026-10-28). AI {@code GET /api/v1/chat/stats}를 서버 키로 불러 관리자에게 넘긴다.
 *
 * <ul>
 *   <li>기간은 여기서 먼저 검사한다(KST 날짜, 둘 다 포함, 최대 31일) - 잘못된 요청을 AI까지 보내지 않는다.</li>
 *   <li>AI 응답은 {@link AdminAiChatStatsResponse} 칸만 옮긴다(허용 목록). 형식이 어긋나면 0으로 메우지 않고 503이다.</li>
 *   <li>AI를 부르지 못하면 화면 전체가 "불러올 수 없음"이다 - 503 {@code AI_STATS_UNAVAILABLE} / 시간 초과 504 {@code AI_STATS_TIMEOUT}.</li>
 *   <li>같은 기간의 성공 결과만 60초 메모리에 둔다(실패는 두지 않음). 응답의 {@code generatedAt}이 집계 시각이다.</li>
 *   <li>챗 킬 스위치({@code chat.relay.enabled})는 전송만 막으므로 여기엔 걸지 않는다. 조회라 감사 로그는 남기지 않는다.</li>
 * </ul>
 */
@Slf4j
@Service
public class AdminAiChatStatsService {

    static final int MAX_RANGE_DAYS = 31;
    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2999;
    static final Duration CACHE_TTL = Duration.ofSeconds(60);
    static final int CACHE_MAX_ENTRIES = 32;
    private static final String RATE_LIMIT_ENDPOINT = "admin-ai-chat-stats";
    private static final int PER_MINUTE = 30;
    private static final int PER_HOUR = 600;
    private static final Pattern DATE = Pattern.compile("\\A\\d{4}-\\d{2}-\\d{2}\\z");
    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);

    private final AiChatClient aiChatClient;
    private final RateLimitService rateLimitService;
    private final LongSupplier nanoTime;
    private final Map<Range, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public AdminAiChatStatsService(AiChatClient aiChatClient, RateLimitService rateLimitService) {
        this(aiChatClient, rateLimitService, System::nanoTime);
    }

    AdminAiChatStatsService(AiChatClient aiChatClient, RateLimitService rateLimitService, LongSupplier nanoTime) {
        this.aiChatClient = aiChatClient;
        this.rateLimitService = rateLimitService;
        this.nanoTime = nanoTime;
    }

    public AdminAiChatStatsResponse getChatStats(String adminId, String rawFrom, String rawTo) {
        Range range = parseRange(rawFrom, rawTo);
        rateLimitService.check(RATE_LIMIT_ENDPOINT, adminId, PER_MINUTE, PER_HOUR);
        long startedAt = nanoTime.getAsLong();

        Cached cached = cache.get(range);
        if (cached != null && startedAt < cached.expiresAtNanos()) {
            log.info("[ADMIN-AI-STATS] 챗 집계 조회: adminId={}, from={}, to={}, cached=true", adminId, range.from(), range.to());
            return cached.response();
        }

        AdminAiChatStatsResponse response;
        try {
            response = toResponse(aiChatClient.stats(range.from(), range.to()), range);
        } catch (CustomException e) {
            ErrorCode mapped = e.getErrorCode() == ErrorCode.CHAT_TIMEOUT ? ErrorCode.AI_STATS_TIMEOUT : ErrorCode.AI_STATS_UNAVAILABLE;
            log.warn("[ADMIN-AI-STATS] 챗 집계 실패: adminId={}, from={}, to={}, code={}", adminId, range.from(), range.to(), mapped);
            throw new CustomException(mapped);
        }
        put(range, response, nanoTime.getAsLong());
        log.info("[ADMIN-AI-STATS] 챗 집계 조회: adminId={}, from={}, to={}, cached=false, elapsedMs={}",
                adminId, range.from(), range.to(), (nanoTime.getAsLong() - startedAt) / 1_000_000);
        return response;
    }

    static Range parseRange(String rawFrom, String rawTo) {
        if (rawFrom == null || rawTo == null || !DATE.matcher(rawFrom).matches() || !DATE.matcher(rawTo).matches()) {
            throw new CustomException(ErrorCode.AI_STATS_INVALID_RANGE);
        }
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(rawFrom);
            to = LocalDate.parse(rawTo);
        } catch (DateTimeParseException e) {
            throw new CustomException(ErrorCode.AI_STATS_INVALID_RANGE);
        }
        // 0001·9999년처럼 서비스 기간과 무관한 날짜는 AI까지 보내지 않는다(AI가 400을 주면 여기선 503으로 보이게 된다).
        if (from.getYear() < MIN_YEAR || to.getYear() > MAX_YEAR
                || from.isAfter(to) || ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw new CustomException(ErrorCode.AI_STATS_INVALID_RANGE);
        }
        return new Range(from, to);
    }

    private void put(Range range, AdminAiChatStatsResponse response, long now) {
        if (cache.size() >= CACHE_MAX_ENTRIES) {
            cache.values().removeIf(entry -> entry.expiresAtNanos() <= now);
            if (cache.size() >= CACHE_MAX_ENTRIES) {
                cache.clear();
            }
        }
        cache.put(range, new Cached(response, now + CACHE_TTL.toNanos()));
    }

    /** AI 응답을 허용 목록 칸으로 옮긴다. 칸이 없거나 타입이 다르거나 기간이 어긋나면 응답 형식 이상(503)이다. */
    private static AdminAiChatStatsResponse toResponse(JsonNode data, Range range) {
        String from = text(data, "from");
        String to = text(data, "to");
        if (!range.from().toString().equals(from) || !range.to().toString().equals(to)) {
            throw malformed();
        }
        JsonNode role = object(data, "byRole");
        JsonNode route = object(data, "byRoute");
        JsonNode dailyNode = data.get("daily");
        long days = ChronoUnit.DAYS.between(range.from(), range.to()) + 1;
        if (dailyNode == null || !dailyNode.isArray() || dailyNode.size() != days) {
            throw malformed();
        }
        List<Daily> daily = new ArrayList<>();
        LocalDate expected = range.from();
        for (JsonNode day : dailyNode) {
            String date = text(day, "date");
            // i번째 줄은 시작일 + i일이어야 한다 - 범위 밖·순서 뒤바뀜·중복 날짜를 한 번에 막는다(2026-10-28 점검 M-1).
            // AI가 날짜를 잘못 묶으면 하루 밀린 그래프가 정상 집계처럼 나가기 때문이다.
            if (!expected.toString().equals(date)) {
                throw malformed();
            }
            daily.add(new Daily(date, count(day, "totalChats")));
            expected = expected.plusDays(1);
        }
        String generatedAt = text(data, "generatedAt");
        try {
            // 계약은 KST(+09:00)다 - 다른 오프셋이면 화면의 "기준 시각"이 틀리게 보인다(점검 L-3).
            if (!KST_OFFSET.equals(OffsetDateTime.parse(generatedAt).getOffset())) {
                throw malformed();
            }
        } catch (DateTimeParseException e) {
            throw malformed();
        }
        return new AdminAiChatStatsResponse(
                from, to,
                count(data, "totalChats"),
                count(data, "users"),
                new ByRole(count(role, "guardian"), count(role, "ward"), count(role, "unknown")),
                new ByRoute(count(route, "reservation"), count(route, "symptom"), count(route, "general"), count(route, "unknown")),
                count(data, "emergencyGuidance"),
                count(data, "reservationCompleted"),
                count(data, "fallbackReplies"),
                List.copyOf(daily),
                generatedAt);
    }

    /** 0 이상의 정수 또는 null(AI가 모른다고 한 값). 칸이 없거나 다른 타입이면 형식 이상. */
    private static Long count(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            throw malformed();
        }
        JsonNode value = node.get(field);
        if (value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw malformed();
        }
        return value.longValue();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual()) {
            throw malformed();
        }
        return value.textValue();
    }

    private static JsonNode object(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) {
            throw malformed();
        }
        return value;
    }

    private static CustomException malformed() {
        return new CustomException(ErrorCode.AI_STATS_UNAVAILABLE);
    }

    record Range(LocalDate from, LocalDate to) {}

    private record Cached(AdminAiChatStatsResponse response, long expiresAtNanos) {}
}

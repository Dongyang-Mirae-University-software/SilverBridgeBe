package kr.silverbridge.main.domain.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.silverbridge.main.domain.admin.dto.AdminAiChatStatsResponse;
import kr.silverbridge.main.domain.chat.client.AiChatClient;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.security.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 관리자 AI 챗 집계 중계(2026-10-28) - 기간 검증·허용 목록 옮기기·형식 검사·오류 코드·캐시. */
@ExtendWith(MockitoExtension.class)
class AdminAiChatStatsServiceTest {

    private static final String ADMIN = "ADM001";
    private static final LocalDate D27 = LocalDate.of(2026, 10, 27);
    private static final LocalDate D28 = LocalDate.of(2026, 10, 28);

    @Mock private AiChatClient aiChatClient;
    @Mock private RateLimitService rateLimitService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private AdminAiChatStatsService service;

    @BeforeEach
    void setUp() {
        service = new AdminAiChatStatsService(aiChatClient, rateLimitService, now::get);
    }

    /** AI #84 응답 data 모양(10/27~10/28). */
    private ObjectNode aiData() throws Exception {
        return (ObjectNode) objectMapper.readTree("""
                {"from":"2026-10-27","to":"2026-10-28","totalChats":9,"users":3,
                 "byRole":{"guardian":5,"ward":3,"unknown":1},
                 "byRoute":{"reservation":4,"symptom":3,"general":2,"unknown":0},
                 "emergencyGuidance":1,"reservationCompleted":2,"fallbackReplies":0,
                 "daily":[{"date":"2026-10-27","totalChats":7},{"date":"2026-10-28","totalChats":2}],
                 "generatedAt":"2026-10-28T11:03:27+09:00",
                 "message":"원문","userIds":["U00001"]}
                """);
    }

    private static void assertCode(Throwable thrown, ErrorCode code) {
        assertThat(thrown).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    @DisplayName("AI 응답을 허용 목록 칸으로 옮기고, 토큰의 관리자 ID로 속도 제한을 건다")
    void maps() throws Exception {
        when(aiChatClient.stats(D27, D28)).thenReturn(aiData());

        AdminAiChatStatsResponse res = service.getChatStats(ADMIN, "2026-10-27", "2026-10-28");

        assertThat(res.from()).isEqualTo("2026-10-27");
        assertThat(res.to()).isEqualTo("2026-10-28");
        assertThat(res.totalChats()).isEqualTo(9L);
        assertThat(res.users()).isEqualTo(3L);
        assertThat(res.byRole()).isEqualTo(new AdminAiChatStatsResponse.ByRole(5L, 3L, 1L));
        assertThat(res.byRoute()).isEqualTo(new AdminAiChatStatsResponse.ByRoute(4L, 3L, 2L, 0L));
        assertThat(res.emergencyGuidance()).isEqualTo(1L);
        assertThat(res.reservationCompleted()).isEqualTo(2L);
        assertThat(res.fallbackReplies()).isZero();
        assertThat(res.daily()).containsExactly(
                new AdminAiChatStatsResponse.Daily("2026-10-27", 7L), new AdminAiChatStatsResponse.Daily("2026-10-28", 2L));
        assertThat(res.generatedAt()).isEqualTo("2026-10-28T11:03:27+09:00");
        // AI가 더 보낸 칸(원문·userId 목록)은 응답 어디에도 없다
        assertThat(objectMapper.writeValueAsString(res)).doesNotContain("원문").doesNotContain("U00001");
        verify(rateLimitService).check("admin-ai-chat-stats", ADMIN, 30, 600);
    }

    @Test
    @DisplayName("AI가 null(모름)을 준 개수는 0으로 채우지 않고 null 그대로")
    void nullStaysNull() throws Exception {
        ObjectNode data = aiData();
        data.putNull("reservationCompleted");
        ((ObjectNode) data.get("byRole")).putNull("unknown");
        when(aiChatClient.stats(D27, D28)).thenReturn(data);

        AdminAiChatStatsResponse res = service.getChatStats(ADMIN, "2026-10-27", "2026-10-28");

        assertThat(res.reservationCompleted()).isNull();
        assertThat(res.byRole().unknown()).isNull();
    }

    @ParameterizedTest(name = "[{index}] from={0}, to={1}")
    @CsvSource(value = {
            "NULL,2026-10-28", "2026-10-28,NULL", "20261027,20261028", "2026-10-1,2026-10-28",
            "2026-02-30,2026-03-01", "2026-10-28,2026-10-27", "2026-10-01,2026-11-01", "'2026-10-27 ',2026-10-28",
            "0001-01-01,0001-01-02", "9999-12-30,9999-12-31", "1999-12-31,2000-01-01"},
            nullValues = "NULL")
    @DisplayName("기간 오류는 400 AI_STATS_INVALID_RANGE - AI도 속도 제한도 건드리지 않는다")
    void invalidRange(String from, String to) {
        assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> service.getChatStats(ADMIN, from, to)),
                ErrorCode.AI_STATS_INVALID_RANGE);
        verifyNoInteractions(aiChatClient, rateLimitService);
    }

    @Test
    @DisplayName("31일은 허용(시작·종료 포함)")
    void thirtyOneDaysOk() throws Exception {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 31);
        ObjectNode data = aiData();
        data.put("from", "2026-10-01");
        data.put("to", "2026-10-31");
        var daily = data.putArray("daily");
        for (int d = 1; d <= 31; d++) {
            daily.addObject().put("date", LocalDate.of(2026, 10, d).toString()).put("totalChats", 0);
        }
        when(aiChatClient.stats(from, to)).thenReturn(data);

        assertThat(service.getChatStats(ADMIN, "2026-10-01", "2026-10-31").daily()).hasSize(31);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource({"칸 없음", "음수", "문자열 숫자", "소수", "기간 불일치", "날짜 줄 수 불일치", "시각 형식 이상", "byRole 객체 아님",
            "날짜 하루 밀림", "날짜 중복", "날짜 순서 뒤바뀜", "날짜 형식 이상", "UTC 오프셋"})
    @DisplayName("AI 응답 형식이 어긋나면 0으로 메우지 않고 503 AI_STATS_UNAVAILABLE")
    void malformed(String kind) throws Exception {
        ObjectNode data = aiData();
        Consumer<ObjectNode> breaker = switch (kind) {
            case "칸 없음" -> d -> d.remove("emergencyGuidance");
            case "음수" -> d -> d.put("users", -1);
            case "문자열 숫자" -> d -> d.put("totalChats", "9");
            case "소수" -> d -> d.put("fallbackReplies", 1.5);
            case "기간 불일치" -> d -> d.put("from", "2026-10-26");
            case "날짜 줄 수 불일치" -> d -> ((com.fasterxml.jackson.databind.node.ArrayNode) d.get("daily")).remove(1);
            case "시각 형식 이상" -> d -> d.put("generatedAt", "어제");
            // 점검 M-1 - 줄 수는 맞지만 날짜가 어긋난 경우
            case "날짜 하루 밀림" -> d -> {
                ((ObjectNode) d.get("daily").get(0)).put("date", "2026-10-26");
                ((ObjectNode) d.get("daily").get(1)).put("date", "2026-10-27");
            };
            case "날짜 중복" -> d -> ((ObjectNode) d.get("daily").get(1)).put("date", "2026-10-27");
            case "날짜 순서 뒤바뀜" -> d -> {
                ((ObjectNode) d.get("daily").get(0)).put("date", "2026-10-28");
                ((ObjectNode) d.get("daily").get(1)).put("date", "2026-10-27");
            };
            case "날짜 형식 이상" -> d -> ((ObjectNode) d.get("daily").get(0)).put("date", "20261027");
            // 점검 L-3 - 같은 순간이라도 +09:00이 아니면 계약 위반
            case "UTC 오프셋" -> d -> d.put("generatedAt", "2026-10-28T02:03:27Z");
            default -> d -> d.put("byRole", 3);
        };
        breaker.accept(data);
        when(aiChatClient.stats(D27, D28)).thenReturn(data);

        assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> service.getChatStats(ADMIN, "2026-10-27", "2026-10-28")),
                ErrorCode.AI_STATS_UNAVAILABLE);
    }

    @Test
    @DisplayName("AI 시간 초과는 504 AI_STATS_TIMEOUT, 그 밖의 AI 오류는 503 AI_STATS_UNAVAILABLE(상담용 문구를 내리지 않는다)")
    void mapsClientErrors() {
        when(aiChatClient.stats(D27, D28)).thenThrow(new CustomException(ErrorCode.CHAT_TIMEOUT));
        assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> service.getChatStats(ADMIN, "2026-10-27", "2026-10-28")),
                ErrorCode.AI_STATS_TIMEOUT);

        doThrow(new CustomException(ErrorCode.CHAT_UNAVAILABLE)).when(aiChatClient).stats(D27, D28);
        assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> service.getChatStats(ADMIN, "2026-10-27", "2026-10-28")),
                ErrorCode.AI_STATS_UNAVAILABLE);
    }

    @Test
    @DisplayName("속도 제한을 넘으면 그 오류 그대로이고 AI를 부르지 않는다")
    void rateLimited() {
        doThrow(new CustomException(ErrorCode.TOO_MANY_REQUESTS))
                .when(rateLimitService).check("admin-ai-chat-stats", ADMIN, 30, 600);

        assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> service.getChatStats(ADMIN, "2026-10-27", "2026-10-28")),
                ErrorCode.TOO_MANY_REQUESTS);
        verifyNoInteractions(aiChatClient);
    }

    @Test
    @DisplayName("같은 기간 성공 결과는 60초 동안 다시 부르지 않고, 60초가 지나면 다시 부른다")
    void cachesSuccessFor60Seconds() throws Exception {
        when(aiChatClient.stats(D27, D28)).thenReturn(aiData());

        AdminAiChatStatsResponse first = service.getChatStats(ADMIN, "2026-10-27", "2026-10-28");
        now.addAndGet(59_000_000_000L);
        assertThat(service.getChatStats("ADM002", "2026-10-27", "2026-10-28")).isSameAs(first);
        verify(aiChatClient, times(1)).stats(D27, D28);

        now.addAndGet(2_000_000_000L);
        service.getChatStats(ADMIN, "2026-10-27", "2026-10-28");
        verify(aiChatClient, times(2)).stats(D27, D28);
    }

    @Test
    @DisplayName("다른 기간은 따로 부르고, 실패한 결과는 캐시하지 않는다")
    void separateRangesAndNoFailureCache() throws Exception {
        when(aiChatClient.stats(D27, D28))
                .thenThrow(new CustomException(ErrorCode.CHAT_UNAVAILABLE))
                .thenReturn(aiData());
        ObjectNode one = aiData();
        one.put("from", "2026-10-28");
        ((com.fasterxml.jackson.databind.node.ArrayNode) one.get("daily")).remove(0);
        when(aiChatClient.stats(D28, D28)).thenReturn(one);

        assertThatThrownBy(() -> service.getChatStats(ADMIN, "2026-10-27", "2026-10-28")).isInstanceOf(CustomException.class);
        assertThat(service.getChatStats(ADMIN, "2026-10-27", "2026-10-28").totalChats()).isEqualTo(9L);
        assertThat(service.getChatStats(ADMIN, "2026-10-28", "2026-10-28").from()).isEqualTo("2026-10-28");

        verify(aiChatClient, times(2)).stats(D27, D28);
        verify(aiChatClient, times(1)).stats(D28, D28);
    }

    @Test
    @DisplayName("캐시는 기간 32개까지만 둔다 - 넘치면 비우고 새로 담는다")
    void cacheIsBounded() throws Exception {
        LocalDate base = LocalDate.of(2026, 9, 1);
        for (int i = 0; i < AdminAiChatStatsService.CACHE_MAX_ENTRIES + 1; i++) {
            LocalDate day = base.plusDays(i);
            ObjectNode data = aiData();
            data.put("from", day.toString());
            data.put("to", day.toString());
            var daily = data.putArray("daily");
            daily.addObject().put("date", day.toString()).put("totalChats", 0);
            when(aiChatClient.stats(day, day)).thenReturn(data);
            service.getChatStats(ADMIN, day.toString(), day.toString());
        }
        // 첫 기간은 넘칠 때 비워졌으므로 다시 부른다
        service.getChatStats(ADMIN, base.toString(), base.toString());
        verify(aiChatClient, times(2)).stats(base, base);
    }
}

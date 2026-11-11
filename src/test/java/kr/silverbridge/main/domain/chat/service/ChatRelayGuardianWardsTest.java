package kr.silverbridge.main.domain.chat.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.chat.client.AiChatClient;
import kr.silverbridge.main.domain.chat.config.ChatRelayProperties;
import kr.silverbridge.main.domain.chat.dto.ChatRelayRequest;
import kr.silverbridge.main.domain.connection.dto.ActiveWardSummary;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.security.RateLimitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 보호자 챗 예약 대상 선택(2026-11-11): guardianWards 는 BE 가 ACTIVE 연결로 만들고(이름·관계만),
 * 예약 대상 선택지(reservation_patient)로 고른 wardId 는 context.wardId 와 같은 기준으로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ChatRelayGuardianWardsTest {

    private static final String GUARDIAN = "GRD001";
    private static final String WARD = "WRD001";

    @Mock private AiChatClient aiChatClient;
    @Mock private RateLimitService rateLimitService;
    @Mock private ConnectionService connectionService;
    @Mock private UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ChatRelayService service;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        ChatRelayProperties properties = new ChatRelayProperties();
        service = new ChatRelayService(aiChatClient, new ChatSlots(properties), rateLimitService, properties,
                objectMapper, connectionService, userRepository);
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(ChatRelayService.class)).addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(ChatRelayService.class)).detachAppender(appender);
    }

    private static ChatRelayRequest request(Map<String, Object> context, ChatRelayRequest.UiSelection selection) {
        return new ChatRelayRequest(selection == null ? "병원 예약해줘" : null, "s1", null, context, selection);
    }

    private static ChatRelayRequest.UiSelection choose(String value) {
        return new ChatRelayRequest.UiSelection(ChatRelayService.PATIENT_CHOICE_FIELD, value);
    }

    private static Map<String, Object> forgedWards() {
        Map<String, Object> context = new HashMap<>();
        context.put("guardianWards", List.of(Map.of("wardId", "VICTIM", "name", "위조이름", "relation", "남", "phone", "010-9999-9999")));
        return context;
    }

    private JsonNode sentContext() throws Exception {
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(aiChatClient).send(body.capture());
        return objectMapper.readTree(body.getValue()).path("context");
    }

    private User ward() {
        return User.builder().id(WARD).name("김영희").phone("010-1234-5678").build();
    }

    private String warnLogs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    @DisplayName("보호자 경로: guardianWards 는 클라이언트 값을 버리고 DB 의 ACTIVE 피보호자 이름·관계만 싣는다")
    void 클라이언트_값_대신_DB_목록() throws Exception {
        when(connectionService.getActiveWardSummaries(GUARDIAN, ChatRelayService.MAX_GUARDIAN_WARDS)).thenReturn(List.of(
                new ActiveWardSummary(WARD, "김영희", "어머니"), new ActiveWardSummary("WRD002", "김철수", null)));
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));

        service.sendAsGuardian(GUARDIAN, request(forgedWards(), null));

        JsonNode wards = sentContext().path("guardianWards");
        assertThat(wards.size()).isEqualTo(2);
        assertThat(wards.get(0).path("wardId").asText()).isEqualTo(WARD);
        assertThat(wards.get(0).path("name").asText()).isEqualTo("김영희");
        assertThat(wards.get(0).path("relation").asText()).isEqualTo("어머니");
        assertThat(wards.get(1).has("relation")).isTrue();
        assertThat(wards.get(1).path("relation").isNull()).isTrue();
        // 이름·관계·ID 외의 칸은 없다(전화·주소·생년월일 금지)
        wards.forEach(item -> assertThat(item.size()).isEqualTo(3));
        assertThat(wards.toString()).doesNotContain("VICTIM").doesNotContain("위조이름").doesNotContain("9999");
    }

    @Test
    @DisplayName("보호자 경로: ACTIVE 피보호자가 없으면 guardianWards 는 빈 배열이다")
    void 피보호자_없으면_빈_배열() throws Exception {
        when(connectionService.getActiveWardSummaries(GUARDIAN, ChatRelayService.MAX_GUARDIAN_WARDS)).thenReturn(List.of());
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));

        service.sendAsGuardian(GUARDIAN, request(forgedWards(), null));

        JsonNode wards = sentContext().path("guardianWards");
        assertThat(wards.isArray()).isTrue();
        assertThat(wards.size()).isZero();
    }

    @Test
    @DisplayName("피보호자 경로: 클라이언트가 보낸 guardianWards 를 지우고 싣지 않는다")
    void 피보호자_경로에는_안_실린다() throws Exception {
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));

        service.send(WARD, request(forgedWards(), null));

        assertThat(sentContext().has("guardianWards")).isFalse();
        verifyNoInteractions(connectionService);
    }

    @Test
    @DisplayName("예약 대상으로 연결되지 않은 wardId 를 고르면 403, AI 를 부르지 않는다")
    void 남의_wardId_선택은_403() {
        when(connectionService.isActiveConnection(GUARDIAN, "VICTIM")).thenReturn(false);

        assertThatThrownBy(() -> service.sendAsGuardian(GUARDIAN, request(null, choose("VICTIM"))))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_WARD_NOT_CONNECTED));
        verifyNoInteractions(aiChatClient);
        assertThat(warnLogs()).contains("[IDOR-ATTEMPT]");
    }

    @Test
    @DisplayName("형식이 틀린 선택 값은 DB 조회 없이 403, 로그에는 길이만 남는다")
    void 형식_틀린_선택은_길이만_로그() {
        String injected = "x\n[IDOR-ATTEMPT] 가짜 줄";

        assertThatThrownBy(() -> service.sendAsGuardian(GUARDIAN, request(null, choose(injected))))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_WARD_NOT_CONNECTED));
        verify(connectionService, never()).isActiveConnection(any(), any());
        verifyNoInteractions(aiChatClient);
        assertThat(warnLogs()).doesNotContain("가짜 줄").contains("valueLength=" + injected.trim().length());
    }

    @Test
    @DisplayName("ACTIVE 피보호자를 고르면 선택을 그대로 AI 에 넘기고, 응답 뒤 연결이 끊겼으면 응답을 폐기한다")
    void ACTIVE_선택은_통과_후_재확인() throws Exception {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true, false);
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));

        assertThatThrownBy(() -> service.sendAsGuardian(GUARDIAN, request(null, choose(WARD))))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_WARD_NOT_CONNECTED));
        verify(aiChatClient).send(any());
    }

    @Test
    @DisplayName("ACTIVE 피보호자를 고르면 uiSelection 이 그대로 AI 로 나간다")
    void ACTIVE_선택은_그대로_전달() throws Exception {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{\"reply\":\"ok\"}"));

        service.sendAsGuardian(GUARDIAN, request(null, choose(WARD)));

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(aiChatClient).send(body.capture());
        JsonNode sent = objectMapper.readTree(body.getValue());
        assertThat(sent.path("uiSelection").path("value").asText()).isEqualTo(WARD);
        assertThat(sent.path("context").has("wardId")).isFalse(); // context.wardId 는 FE 가 실을 때만 BE 가 채운다
    }

    @Test
    @DisplayName("같은 턴의 context.wardId 와 선택 값이 다르면 400, AI 를 부르지 않는다")
    void context_wardId와_다르면_400() {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
        when(userRepository.findById(WARD)).thenReturn(Optional.of(ward()));
        Map<String, Object> context = new HashMap<>();
        context.put("wardId", WARD);

        assertThatThrownBy(() -> service.sendAsGuardian(GUARDIAN, request(context, choose("WRD002"))))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_INVALID_REQUEST));
        verifyNoInteractions(aiChatClient);
    }

    @Test
    @DisplayName("\"나\"(__self__) 선택과 다른 칸의 선택은 연결 검증 없이 지나간다")
    void 나_선택과_다른_칸은_검증_없음() throws Exception {
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));

        service.sendAsGuardian(GUARDIAN, request(null, choose(ChatRelayService.SELF_CHOICE)));
        service.sendAsGuardian(GUARDIAN, request(null, new ChatRelayRequest.UiSelection("time", "0930")));

        verify(connectionService, never()).isActiveConnection(any(), any());
    }

    @Test
    @DisplayName("피보호자 경로에서 예약 대상으로 누군가를 고르면 403(나 선택은 통과)")
    void 피보호자_경로_선택은_403() throws Exception {
        assertThatThrownBy(() -> service.send(WARD, request(null, choose("GRD001"))))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_WARD_NOT_CONNECTED));
        verifyNoInteractions(aiChatClient);

        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));
        service.send(WARD, request(null, choose(ChatRelayService.SELF_CHOICE)));
        verify(aiChatClient).send(any());
    }

    @Test
    @DisplayName("기록 응답에는 guardianWards 가 나오지 않는다(허용 목록 불변)")
    void 기록_응답에_없음() throws Exception {
        when(aiChatClient.logs(GUARDIAN)).thenReturn(objectMapper.readTree("""
                [{"chatId":1,"message":"예약","reply":"누구 예약인가요?","guardianWards":[{"wardId":"WRD001","name":"김영희"}],
                  "contextJson":{"userContext":{"guardianWards":[{"wardId":"WRD001","name":"김영희"}]}}}]
                """));

        Object logs = service.logs(GUARDIAN);

        String json = objectMapper.writeValueAsString(logs);
        assertThat(json).doesNotContain("guardianWards").doesNotContain("김영희");
    }

    @Test
    @DisplayName("guardianWards 를 채워도 context 8KB 상한은 최종 크기로 본다(상한 20명은 넉넉히 들어간다)")
    void 상한_20명은_8KB_안() throws Exception {
        List<ActiveWardSummary> many = new java.util.ArrayList<>();
        for (int i = 0; i < ChatRelayService.MAX_GUARDIAN_WARDS; i++) {
            many.add(new ActiveWardSummary("W" + String.format("%05d", i), "가".repeat(20), "나".repeat(10))); // users.name 20 · connection.relation 10 최대
        }
        when(connectionService.getActiveWardSummaries(any(), anyInt())).thenReturn(many);
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));

        service.sendAsGuardian(GUARDIAN, request(null, null));

        assertThat(sentContext().path("guardianWards").size()).isEqualTo(ChatRelayService.MAX_GUARDIAN_WARDS);
    }
}

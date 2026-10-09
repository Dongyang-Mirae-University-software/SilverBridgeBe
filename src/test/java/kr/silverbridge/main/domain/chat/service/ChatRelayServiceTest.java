package kr.silverbridge.main.domain.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.chat.client.AiChatClient;
import kr.silverbridge.main.domain.chat.config.ChatRelayProperties;
import kr.silverbridge.main.domain.chat.dto.ChatRelayRequest;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.security.RateLimitService;
import kr.silverbridge.main.global.exception.TooManyRequestsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatRelayServiceTest {

    private static final String ME = "GRD001";

    @Mock
    private AiChatClient aiChatClient;
    @Mock
    private RateLimitService rateLimitService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ChatRelayProperties properties;
    private ChatSlots slots;
    private ChatRelayService service;

    @BeforeEach
    void setUp() {
        properties = new ChatRelayProperties();
        slots = new ChatSlots(properties);
        service = new ChatRelayService(aiChatClient, slots, rateLimitService, properties, objectMapper);
    }

    private static ChatRelayRequest msg(String text) {
        return new ChatRelayRequest(text, "sess-1", null, null, null);
    }

    @Test
    @DisplayName("AI로 나가는 userId는 항상 토큰의 ID - 본문의 userId는 DTO에 필드가 없어 무시된다")
    void 토큰_ID만_AI로_전달() throws Exception {
        // FE가 userId를 다른 사람 값으로 보내도 DTO에 없어 역직렬화에서 버려진다
        ChatRelayRequest request = objectMapper.readValue(
                "{\"message\":\"두통\",\"userId\":\"VICTIM\",\"sessionId\":\"s\"}", ChatRelayRequest.class);
        JsonNode reply = objectMapper.readTree("{\"reply\":\"ok\"}");
        when(aiChatClient.send(any())).thenReturn(reply);

        assertThat(service.send(ME, request)).isEqualTo(java.util.Map.of("reply", "ok"));

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(aiChatClient).send(body.capture());
        JsonNode sent = objectMapper.readTree(body.getValue());
        assertThat(sent.path("userId").asText()).isEqualTo(ME);
        assertThat(sent.path("message").asText()).isEqualTo("두통");
        assertThat(body.getValue()).asString().doesNotContain("VICTIM");
    }

    @Test
    @DisplayName("context 안에 userId가 있어도 최상위 userId는 토큰 ID다")
    void context의_userId는_최상위를_바꾸지_못한다() throws Exception {
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));
        ChatRelayRequest request = new ChatRelayRequest("안녕", null, null,
                Map.of("userId", "VICTIM", "name", "홍길동"), null);

        service.send(ME, request);

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(aiChatClient).send(body.capture());
        assertThat(objectMapper.readTree(body.getValue()).path("userId").asText()).isEqualTo(ME);
    }

    @Test
    @DisplayName("기록 목록·상세는 항상 토큰 ID로만 AI에 묻는다")
    void 기록은_토큰_ID로만_조회() throws Exception {
        when(aiChatClient.logs(ME)).thenReturn(objectMapper.readTree("[]"));
        when(aiChatClient.logDetail(ME, 5L)).thenReturn(Optional.of(objectMapper.readTree("{\"id\":5}")));

        service.logs(ME);
        service.logDetail(ME, 5L);

        verify(aiChatClient).logs(ME);
        verify(aiChatClient).logDetail(ME, 5L);
    }

    @Test
    @DisplayName("남의(또는 없는) 기록 상세 → 404 CHAT_LOG_NOT_FOUND")
    void 남의_기록은_404() {
        when(aiChatClient.logDetail(ME, 9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.logDetail(ME, 9L))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_LOG_NOT_FOUND));
    }

    @Test
    @DisplayName("메시지·uiSelection이 모두 없거나 길이 상한을 넘으면 400, AI를 부르지 않는다")
    void 입력_검증() {
        assertThatThrownBy(() -> service.send(ME, msg("  ")))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_INVALID_REQUEST));
        assertThatThrownBy(() -> service.send(ME, msg("가".repeat(2001))))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_INVALID_REQUEST));
        verifyNoInteractions(aiChatClient);
    }

    @Test
    @DisplayName("uiSelection만 있어도 전송된다(message 없음)")
    void uiSelection만_허용() throws Exception {
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));
        ChatRelayRequest request = new ChatRelayRequest(null, null, List.of(), null,
                new ChatRelayRequest.UiSelection("date", "2026-10-08"));

        service.send(ME, request);

        verify(aiChatClient).send(any());
    }

    @Test
    @DisplayName("속도 제한 초과(429)면 AI를 부르지 않는다")
    void 속도제한_초과() {
        doThrow(new TooManyRequestsException(30L))
                .when(rateLimitService).check(eq("chat-send"), eq(ME), anyInt(), anyInt());

        assertThatThrownBy(() -> service.send(ME, msg("안녕"))).isInstanceOf(TooManyRequestsException.class);
        verifyNoInteractions(aiChatClient);
    }

    @Test
    @DisplayName("같은 보호자의 동시 전송은 1건 - 두 번째는 429 CHAT_LIMIT_EXCEEDED, 끝나면 자리가 돌아온다")
    void 동시상한_1인() throws Exception {
        JsonNode reply = objectMapper.readTree("{}");
        when(aiChatClient.send(any())).thenAnswer(inv -> {
            // 첫 호출이 진행 중일 때 두 번째 전송을 시도한다
            assertThatThrownBy(() -> service.send(ME, msg("두번째")))
                    .isInstanceOfSatisfying(CustomException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_LIMIT_EXCEEDED));
            // 다른 보호자는 영향이 없다
            service.send("GRD002", msg("다른 사람"));
            return reply;
        }).thenReturn(reply);

        service.send(ME, msg("첫번째"));

        assertThat(slots.activeUsers()).isZero();
        service.send(ME, msg("세번째"));   // 자리 반환 확인
    }

    @Test
    @DisplayName("보호자·피보호자가 같은 서비스를 써도 AI에는 각자 토큰 ID만 가고, 한도·동시 자리는 사용자 ID별로 따로 센다")
    void 역할이_달라도_사용자별로_격리() throws Exception {
        JsonNode reply = objectMapper.readTree("{\"reply\":\"ok\"}");
        when(aiChatClient.send(any())).thenAnswer(inv -> {
            // 보호자 전송이 진행 중이어도 피보호자 전송은 막히지 않는다
            service.send("WRD001", msg("피보호자"));
            return reply;
        }).thenReturn(reply);
        when(aiChatClient.logs(anyString())).thenReturn(objectMapper.readTree("[]"));

        service.send(ME, msg("보호자"));
        service.logs("WRD001");

        verify(rateLimitService).check(eq("chat-send"), eq("GRD001"), anyInt(), anyInt());
        verify(rateLimitService).check(eq("chat-send"), eq("WRD001"), anyInt(), anyInt());
        verify(aiChatClient).logs("WRD001");
        verify(aiChatClient, never()).logs("GRD001");
        assertThat(slots.activeUsers()).isZero();
    }

    @Test
    @DisplayName("AI 실패여도 자리를 돌려준다")
    void 실패해도_자리_반환() {
        when(aiChatClient.send(any())).thenThrow(new CustomException(ErrorCode.CHAT_UNAVAILABLE));

        assertThatThrownBy(() -> service.send(ME, msg("안녕"))).isInstanceOf(CustomException.class);

        assertThat(slots.activeUsers()).isZero();
    }

    @Test
    @DisplayName("서버 전체 동시 상한을 넘으면 429이고 1인 자리도 돌려준다")
    void 동시상한_전체() {
        properties.setMaxConcurrent(1);
        ChatSlots small = new ChatSlots(properties);
        ChatSlots.Slot held = small.acquire("A");

        assertThatThrownBy(() -> small.acquire("B"))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_LIMIT_EXCEEDED));
        assertThat(small.activeUsers()).isEqualTo(1);
        held.close();
        small.acquire("B").close();
    }

    @Test
    @DisplayName("킬 스위치가 꺼지면 전송만 503, 기록 조회는 계속된다")
    void 킬스위치() throws Exception {
        properties.setEnabled(false);
        when(aiChatClient.logs(ME)).thenReturn(objectMapper.readTree("[]"));

        assertThatThrownBy(() -> service.send(ME, msg("안녕")))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_UNAVAILABLE));
        verify(aiChatClient, never()).send(any());
        assertThat(service.logs(ME)).isNotNull();
    }

    @Test
    @DisplayName("기록 목록·상세는 허용된 필드만 내리고 AI 내부 정보(contextJson·upstreamMeta·decisionTrace·userId)는 뺀다")
    void 기록_응답은_허용목록만() throws Exception {
        String raw = "{\"id\":5,\"userId\":\"GRD001\",\"message\":\"두통\",\"reply\":\"쉬세요\","
                + "\"contextJson\":\"{\\\"userContext\\\":{\\\"phone\\\":\\\"01012345678\\\"}}\","
                + "\"upstreamMeta\":{\"router\":\"x\"},\"decisionTrace\":[\"a\"],\"toolData\":{\"k\":1},"
                + "\"createdAt\":\"2026-10-07T10:00:00\"}";
        when(aiChatClient.logs(ME)).thenReturn(objectMapper.readTree("[" + raw + "]"));
        when(aiChatClient.logDetail(ME, 5L)).thenReturn(Optional.of(objectMapper.readTree(raw)));

        JsonNode list = objectMapper.valueToTree(service.logs(ME));
        JsonNode detail = objectMapper.valueToTree(service.logDetail(ME, 5L));

        for (JsonNode item : new JsonNode[]{list.get(0), detail}) {
            assertThat(item.get("message").asText()).isEqualTo("두통");
            assertThat(item.get("toolData").get("k").asInt()).isEqualTo(1);
            assertThat(item.has("contextJson")).isFalse();
            assertThat(item.has("upstreamMeta")).isFalse();
            assertThat(item.has("decisionTrace")).isFalse();
            assertThat(item.has("userId")).isFalse();
            assertThat(item.toString()).doesNotContain("01012345678");
        }
    }

    @Test
    @DisplayName("AI 기록 응답 형식이 이상하면(목록이 배열 아님·상세가 객체 아님) CHAT_UNAVAILABLE")
    void 기록_형식_이상() throws Exception {
        when(aiChatClient.logs(ME)).thenReturn(objectMapper.readTree("{\"not\":\"array\"}"));
        when(aiChatClient.logDetail(ME, 5L)).thenReturn(Optional.of(objectMapper.readTree("[1]")));

        assertThatThrownBy(() -> service.logs(ME)).isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_UNAVAILABLE));
        assertThatThrownBy(() -> service.logDetail(ME, 5L)).isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_UNAVAILABLE));
    }

    @Test
    @DisplayName("context는 직렬화 8192바이트까지 허용, 8193바이트부터 400")
    void context_8KB_경계() throws Exception {
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));
        // {"k":"<n자>"} = n + 8 바이트
        ChatRelayRequest atLimit = new ChatRelayRequest("안녕", null, null, Map.of("k", "a".repeat(8184)), null);
        ChatRelayRequest over = new ChatRelayRequest("안녕", null, null, Map.of("k", "a".repeat(8185)), null);

        service.send(ME, atLimit);
        assertThatThrownBy(() -> service.send(ME, over)).isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_INVALID_REQUEST));
    }

    @Test
    @DisplayName("message는 2000자까지 허용(경계)")
    void message_2000자_허용() throws Exception {
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));

        service.send(ME, msg("가".repeat(2000)));

        verify(aiChatClient).send(any());
    }
}

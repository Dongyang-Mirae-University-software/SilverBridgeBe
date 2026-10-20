package kr.silverbridge.main.domain.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.chat.client.AiChatClient;
import kr.silverbridge.main.domain.chat.config.ChatRelayProperties;
import kr.silverbridge.main.domain.chat.dto.ChatRelayRequest;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.security.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 대리 상담: wardId 는 BE 가 ACTIVE 연결로 검증하고, 이름·전화는 DB 값으로만 AI 에 나간다(2026-10-20). */
@ExtendWith(MockitoExtension.class)
class ChatRelayWardContextTest {

    private static final String GUARDIAN = "GRD001";
    private static final String WARD = "WRD001";

    @Mock
    private AiChatClient aiChatClient;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private ConnectionService connectionService;
    @Mock
    private UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ChatRelayService service;

    @BeforeEach
    void setUp() {
        ChatRelayProperties properties = new ChatRelayProperties();
        service = new ChatRelayService(aiChatClient, new ChatSlots(properties), rateLimitService, properties,
                objectMapper, connectionService, userRepository);
    }

    private static ChatRelayRequest withContext(Map<String, Object> context) {
        return new ChatRelayRequest("병원 예약해줘", "s1", null, context, null);
    }

    private static Map<String, Object> forged(String wardId) {
        Map<String, Object> context = new HashMap<>();
        context.put("wardId", wardId);
        context.put("wardName", "위조이름");
        context.put("wardPhone", "010-9999-9999");
        context.put("name", "보호자");
        return context;
    }

    private JsonNode sentBody() throws Exception {
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(aiChatClient).send(body.capture());
        return objectMapper.readTree(body.getValue());
    }

    private User ward() {
        return User.builder().id(WARD).name("김영희").phone("010-1234-5678").build();
    }

    @Test
    @DisplayName("연결되지 않은 wardId → 403 CHAT_WARD_NOT_CONNECTED, AI 호출 없음")
    void 연결_안_된_wardId는_거절() {
        when(connectionService.isActiveConnection(GUARDIAN, "VICTIM")).thenReturn(false);

        assertThatThrownBy(() -> service.sendAsGuardian(GUARDIAN, withContext(forged("VICTIM"))))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_WARD_NOT_CONNECTED));

        verifyNoInteractions(aiChatClient);
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("ACTIVE 연결이 아닌 경우(PENDING 포함)는 isActiveConnection=false 라 같은 403")
    void PENDING_연결도_거절() {
        // isActiveConnection 은 ACTIVE 만 true 를 돌려준다 - PENDING 은 false
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(false);

        assertThatThrownBy(() -> service.sendAsGuardian(GUARDIAN, withContext(forged(WARD))))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_WARD_NOT_CONNECTED));
        verifyNoInteractions(aiChatClient);
    }

    @Test
    @DisplayName("ACTIVE 연결이면 wardName·wardPhone 은 클라이언트 값 대신 DB 값으로 나간다")
    void ACTIVE면_DB_값으로_덮어쓴다() throws Exception {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
        when(userRepository.findById(WARD)).thenReturn(Optional.of(ward()));
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));

        service.sendAsGuardian(GUARDIAN, withContext(forged(WARD)));

        JsonNode body = sentBody();
        JsonNode context = body.path("context");
        assertThat(context.path("wardId").asText()).isEqualTo(WARD);
        assertThat(context.path("wardName").asText()).isEqualTo("김영희");
        assertThat(context.path("wardPhone").asText()).isEqualTo("010-1234-5678");
        assertThat(context.toString()).doesNotContain("위조이름").doesNotContain("9999");
        assertThat(body.path("userId").asText()).isEqualTo(GUARDIAN);
    }

    @Test
    @DisplayName("wardId 가 문자열이 아니면 400")
    void 숫자_wardId는_400() {
        Map<String, Object> numeric = new HashMap<>();
        numeric.put("wardId", 123);

        assertThatThrownBy(() -> service.sendAsGuardian(GUARDIAN, withContext(numeric)))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_INVALID_REQUEST));
        verifyNoInteractions(aiChatClient);
    }

    @Test
    @DisplayName("빈 wardId 는 본인 상담 - 대리 키(wardName·wardPhone 포함)는 모두 지운다")
    void 빈_wardId는_대리_키를_지운다() throws Exception {
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));

        service.sendAsGuardian(GUARDIAN, withContext(forged("  ")));

        JsonNode context = sentBody().path("context");
        assertThat(context.has("wardId")).isFalse();
        assertThat(context.has("wardName")).isFalse();
        assertThat(context.has("wardPhone")).isFalse();
        assertThat(context.path("name").asText()).isEqualTo("보호자");
        verifyNoInteractions(connectionService);
    }

    @Test
    @DisplayName("피보호자(본인) 경로: context 의 wardId·wardName·wardPhone 을 지운다")
    void 피보호자_경로는_대리_키를_지운다() throws Exception {
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));

        service.send(WARD, withContext(forged("VICTIM")));

        JsonNode context = sentBody().path("context");
        assertThat(context.has("wardId")).isFalse();
        assertThat(context.has("wardName")).isFalse();
        assertThat(context.has("wardPhone")).isFalse();
        assertThat(context.path("name").asText()).isEqualTo("보호자");
        verifyNoInteractions(connectionService, userRepository);
    }
}

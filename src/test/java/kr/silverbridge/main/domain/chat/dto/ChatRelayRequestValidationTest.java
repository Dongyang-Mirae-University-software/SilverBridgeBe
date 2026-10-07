package kr.silverbridge.main.domain.chat.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 챗 전송 요청 검증 - 경계값과 잘못된 값이 400(검증 실패)으로 걸러지는지. */
class ChatRelayRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static ChatRelayRequest request(String sessionId, List<ChatRelayRequest.HistoryItem> history,
                                            Map<String, Object> context, ChatRelayRequest.UiSelection ui) {
        return new ChatRelayRequest("안녕", sessionId, history, context, ui);
    }

    @Test
    @DisplayName("정상 요청·필드 생략은 통과")
    void 정상() {
        assertThat(validator.validate(request(null, null, null, null))).isEmpty();
        assertThat(validator.validate(request("s".repeat(64), List.of(
                new ChatRelayRequest.HistoryItem("user", "a"),
                new ChatRelayRequest.HistoryItem("assistant", "b".repeat(4000))), Map.of("name", "홍길동"),
                new ChatRelayRequest.UiSelection("date", "2026-10-08")))).isEmpty();
    }

    @Test
    @DisplayName("sessionId 65자 → 위반")
    void sessionId_상한() {
        assertThat(validator.validate(request("s".repeat(65), null, null, null))).hasSize(1);
    }

    @Test
    @DisplayName("history 25개 → 위반, 24개는 통과")
    void history_개수() {
        List<ChatRelayRequest.HistoryItem> items24 = IntStream.range(0, 24)
                .mapToObj(i -> new ChatRelayRequest.HistoryItem("user", "x")).toList();
        List<ChatRelayRequest.HistoryItem> items25 = IntStream.range(0, 25)
                .mapToObj(i -> new ChatRelayRequest.HistoryItem("user", "x")).toList();

        assertThat(validator.validate(request(null, items24, null, null))).isEmpty();
        assertThat(validator.validate(request(null, items25, null, null))).hasSize(1);
    }

    @Test
    @DisplayName("history 항목: role이 user·assistant가 아니거나 content가 null·4001자면 위반")
    void history_항목() {
        assertThat(validator.validate(request(null, List.of(new ChatRelayRequest.HistoryItem("system", "x")), null, null)))
                .isNotEmpty();
        assertThat(validator.validate(request(null, List.of(new ChatRelayRequest.HistoryItem("user", null)), null, null)))
                .isNotEmpty();
        assertThat(validator.validate(request(null, List.of(new ChatRelayRequest.HistoryItem("user", "x".repeat(4001))), null, null)))
                .isNotEmpty();
    }

    @Test
    @DisplayName("context 21개 키 → 위반, 20개는 통과")
    void context_키_개수() {
        Map<String, Object> keys20 = new LinkedHashMap<>();
        IntStream.range(0, 20).forEach(i -> keys20.put("k" + i, i));
        Map<String, Object> keys21 = new LinkedHashMap<>(keys20);
        keys21.put("k20", 20);

        assertThat(validator.validate(request(null, null, keys20, null))).isEmpty();
        assertThat(validator.validate(request(null, null, keys21, null))).hasSize(1);
    }

    @Test
    @DisplayName("uiSelection의 field·value가 비었거나 너무 길면 위반")
    void uiSelection() {
        assertThat(validator.validate(request(null, null, null, new ChatRelayRequest.UiSelection("", "v")))).isNotEmpty();
        assertThat(validator.validate(request(null, null, null, new ChatRelayRequest.UiSelection("f", " ")))).isNotEmpty();
        assertThat(validator.validate(request(null, null, null, new ChatRelayRequest.UiSelection("f".repeat(101), "v")))).isNotEmpty();
        assertThat(validator.validate(request(null, null, null, new ChatRelayRequest.UiSelection("f", "v".repeat(501))))).isNotEmpty();
    }
}

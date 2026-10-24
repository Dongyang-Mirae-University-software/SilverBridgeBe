package kr.silverbridge.main.domain.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatLogProjectionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("허용 목록 밖 필드는 AI가 새로 더해도 자동으로 빠진다(차단 목록이 아니다)")
    void 새_필드도_빠진다() throws Exception {
        JsonNode log = mapper.readTree("{\"id\":1,\"reply\":\"a\",\"brandNewInternalField\":\"secret\"}");

        JsonNode out = ChatLogProjection.project(log);

        assertThat(out.has("brandNewInternalField")).isFalse();
        assertThat(out.get("id").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("값은 그대로 옮기고 null 값 필드(tool=null 등)도 유지한다")
    void 값_보존() throws Exception {
        JsonNode log = mapper.readTree("{\"tool\":null,\"ui\":{\"kind\":\"date\"},\"reservationRequired\":false}");

        JsonNode out = ChatLogProjection.project(log);

        assertThat(out.has("tool")).isTrue();
        assertThat(out.get("tool").isNull()).isTrue();
        assertThat(out.get("ui").get("kind").asText()).isEqualTo("date");
        assertThat(out.get("reservationRequired").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("응급·위기 경고 박스 문구(emergencyWarning)는 기록 조회에도 실린다")
    void 경고박스_유지() throws Exception {
        JsonNode log = mapper.readTree("{\"id\":1,\"reply\":\"a\",\"emergencyWarning\":[\"바로 119\"]}");

        JsonNode out = ChatLogProjection.project(log);

        assertThat(out.get("emergencyWarning").isArray()).isTrue();
        assertThat(out.get("emergencyWarning").get(0).asText()).isEqualTo("바로 119");
    }

    @Test
    @DisplayName("빈 배열은 빈 배열, 객체 아닌 입력은 null")
    void 경계() throws Exception {
        assertThat(ChatLogProjection.projectAll(mapper.readTree("[]"))).isEmpty();
        assertThat(ChatLogProjection.projectAll(mapper.readTree("{}"))).isNull();
        assertThat(ChatLogProjection.projectAll(mapper.readTree("[1]"))).isNull();
        assertThat(ChatLogProjection.project(mapper.readTree("[]"))).isNull();
        assertThat(ChatLogProjection.project(null)).isNull();
    }

    @Test
    @DisplayName("허용 필드 목록에 내부 정보 필드가 들어 있지 않다")
    void 허용목록에_내부정보_없음() {
        assertThat(ChatLogProjection.ALLOWED_FIELDS)
                .doesNotContain("contextJson", "upstreamMeta", "decisionTrace", "userId");
    }
}

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
    @DisplayName("layout 안의 모르는 하위 칸·문자열이 아닌 줄은 빠지고, 객체가 아닌 layout 은 null 이 된다(2026-11-01 점검 L-5)")
    void 답모양_하위칸_허용목록() throws Exception {
        JsonNode log = mapper.readTree("{\"id\":1,\"layout\":{\"headline\":\"h\",\"body\":[\"b\",3,{\"x\":1},null],"
                + "\"questions\":\"q\",\"visitHospitalIf\":[\"v\"],\"debug\":{\"userId\":\"u1\"},\"trace\":[1]}}");
        JsonNode badHeadline = mapper.readTree("{\"id\":2,\"layout\":{\"headline\":{\"a\":1}}}");
        JsonNode notObject = mapper.readTree("{\"id\":3,\"layout\":[\"a\"]}");

        JsonNode out = ChatLogProjection.project(log).get("layout");

        assertThat(out.has("debug")).isFalse();
        assertThat(out.has("trace")).isFalse();
        assertThat(out.toString()).doesNotContain("u1");
        assertThat(out.get("headline").asText()).isEqualTo("h");
        assertThat(out.get("body")).hasSize(1);
        assertThat(out.get("body").get(0).asText()).isEqualTo("b");
        assertThat(out.get("questions").isArray()).isTrue();
        assertThat(out.get("questions")).isEmpty();
        assertThat(out.get("homeCare")).isEmpty();
        assertThat(out.get("visitHospitalIf").get(0).asText()).isEqualTo("v");
        assertThat(ChatLogProjection.project(badHeadline).get("layout").get("headline").isNull()).isTrue();
        assertThat(ChatLogProjection.project(notObject).get("layout").isNull()).isTrue();
    }

    @Test
    @DisplayName("layout.urgent 는 불리언 true 일 때만 내리고 문자열·숫자·false·객체는 뺀다(2026-11-09 FE #229 점검 L-6)")
    void 답모양_긴급표시는_불리언_true만() throws Exception {
        JsonNode on = mapper.readTree("{\"id\":1,\"layout\":{\"headline\":\"h\",\"urgent\":true}}");
        assertThat(ChatLogProjection.project(on).get("layout").get("urgent").asBoolean()).isTrue();
        for (String value : new String[] {"\"true\"", "1", "false", "null", "{\"a\":1}"}) {
            JsonNode off = mapper.readTree("{\"id\":2,\"layout\":{\"headline\":\"h\",\"urgent\":" + value + "}}");
            assertThat(ChatLogProjection.project(off).get("layout").has("urgent")).as(value).isFalse();
        }
        JsonNode none = mapper.readTree("{\"id\":3,\"layout\":{\"headline\":\"h\"}}");
        assertThat(ChatLogProjection.project(none).get("layout").has("urgent")).isFalse();
    }

    @Test
    @DisplayName("답 모양(layout)은 기록 조회에도 그대로 실리고, 옛 기록의 null 도 유지한다(2026-11-01)")
    void 답모양_유지() throws Exception {
        JsonNode log = mapper.readTree("{\"id\":1,\"reply\":\"a\",\"layout\":{\"headline\":\"h\",\"body\":[\"b\"],"
                + "\"questions\":[],\"homeCare\":[],\"visitHospitalIf\":[\"v\"]}}");
        JsonNode old = mapper.readTree("{\"id\":2,\"reply\":\"a\",\"layout\":null}");

        JsonNode out = ChatLogProjection.project(log);

        assertThat(out.get("layout").get("headline").asText()).isEqualTo("h");
        assertThat(out.get("layout").get("visitHospitalIf").get(0).asText()).isEqualTo("v");
        assertThat(ChatLogProjection.project(old).get("layout").isNull()).isTrue();
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
    @DisplayName("대리 상담 기록의 wardId 는 사용자 ID 형식일 때만 내리고, 본인 상담·형식이 틀린 값은 null (2026-11-11)")
    void 대리상담_wardId() throws Exception {
        assertThat(ChatLogProjection.project(mapper.readTree("{\"id\":1,\"wardId\":\"aB3x9Z\"}")).get("wardId").asText())
                .isEqualTo("aB3x9Z");
        for (String raw : new String[] {"null", "\"\"", "\"a\\nb\"", "\"" + "x".repeat(65) + "\"", "123", "{\"id\":1}"}) {
            JsonNode out = ChatLogProjection.project(mapper.readTree("{\"id\":1,\"wardId\":" + raw + "}"));
            assertThat(out.get("wardId").isNull()).as(raw).isTrue();
        }
        assertThat(ChatLogProjection.project(mapper.readTree("{\"id\":1}")).has("wardId")).isFalse();
    }

    @Test
    @DisplayName("허용 필드 목록에 내부 정보 필드가 들어 있지 않다")
    void 허용목록에_내부정보_없음() {
        assertThat(ChatLogProjection.ALLOWED_FIELDS)
                .doesNotContain("contextJson", "upstreamMeta", "decisionTrace", "userId");
    }
}

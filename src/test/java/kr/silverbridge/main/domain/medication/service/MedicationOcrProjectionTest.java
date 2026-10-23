package kr.silverbridge.main.domain.medication.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.medication.dto.MedicationOcrResponse;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** AI OCR 응답 허용 목록 거르기 - 칸별로 범위 밖이면 그 칸만 비우고, 모르는 필드는 응답 타입에 자리가 없어 실리지 않는다. */
class MedicationOcrProjectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    @Test
    @DisplayName("정상: 반 알·횟수·일수·시간대·안내·확신을 옮기고 reason·성분·모르는 필드는 버린다")
    void projectsAllowedFields() throws Exception {
        MedicationOcrResponse r = MedicationOcrProjection.project(json("""
                {"readable":true,"reason":"홍길동 님 처방","patientName":"홍길동",
                 "drugs":[{"name":"슈다페드정","ingredient":"pseudoephedrine","perDose":0.5,"timesPerDay":2,"days":5,
                           "slots":["DINNER","MORNING"],"timing":"식후 30분","confidence":"high","hospital":"OO의원"}]}"""));

        assertThat(r.readable()).isTrue();
        assertThat(r.drugs()).containsExactly(new MedicationOcrResponse.Drug("슈다페드정", 0.5, 2, 5,
                java.util.List.of(MedicationTimeSlot.MORNING, MedicationTimeSlot.DINNER), "식후 30분", "high", null, java.util.List.of()));
        // 응답 레코드에는 reason·ingredient 등의 자리가 없다
        assertThat(MAPPER.writeValueAsString(r)).doesNotContain("reason", "ingredient", "홍길동", "OO의원");
    }

    @Test
    @DisplayName("readable=false 면 AI가 약을 보내도 빈 목록이다")
    void unreadableForcesEmpty() throws Exception {
        MedicationOcrResponse r = MedicationOcrProjection.project(json(
                "{\"readable\":false,\"reason\":\"메모\",\"drugs\":[{\"name\":\"x\",\"confidence\":\"low\"}]}"));

        assertThat(r.readable()).isFalse();
        assertThat(r.drugs()).isEmpty();
    }

    @Test
    @DisplayName("형식이 계약과 다르면 null(호출부 503): readable 없음·boolean 아님·drugs 가 배열 아님·객체 아님")
    void malformedIsNull() throws Exception {
        assertThat(MedicationOcrProjection.project(json("{\"drugs\":[]}"))).isNull();
        assertThat(MedicationOcrProjection.project(json("{\"readable\":\"true\",\"drugs\":[]}"))).isNull();
        assertThat(MedicationOcrProjection.project(json("{\"readable\":true,\"drugs\":{}}"))).isNull();
        assertThat(MedicationOcrProjection.project(json("[]"))).isNull();
        assertThat(MedicationOcrProjection.project(null)).isNull();
        // drugs 가 없거나 null 이면 빈 목록
        assertThat(MedicationOcrProjection.project(json("{\"readable\":true}")).drugs()).isEmpty();
    }

    @Test
    @DisplayName("시간대: 모르는 값·문자열 아님은 버리고 중복은 한 번, 순서는 아침→취침 전")
    void slotsFiltered() throws Exception {
        MedicationOcrResponse r = MedicationOcrProjection.project(json("""
                {"readable":true,"drugs":[{"name":"a","slots":["BEDTIME","NOON","morning",3,"MORNING","BEDTIME"]}]}"""));

        assertThat(r.drugs().get(0).slots()).containsExactly(MedicationTimeSlot.MORNING, MedicationTimeSlot.BEDTIME);
    }

    @Test
    @DisplayName("범위 밖·형식 다른 칸은 그 칸만 null - 약은 남는다")
    void outOfRangeFieldsAreNull() throws Exception {
        MedicationOcrResponse r = MedicationOcrProjection.project(json("""
                {"readable":true,"drugs":[
                  {"name":"a","perDose":0,"timesPerDay":0,"days":400,"timing":"   ","confidence":"medium"},
                  {"name":"b","perDose":"1","timesPerDay":1.5,"days":-1,"timing":3,"confidence":1},
                  {"name":"c","perDose":100,"timesPerDay":11,"days":1,"slots":"MORNING"}]}"""));

        assertThat(r.drugs()).extracting(MedicationOcrResponse.Drug::name).containsExactly("a", "b", "c");
        assertThat(r.drugs()).allSatisfy(d -> {
            assertThat(d.perDose()).isNull();
            assertThat(d.timesPerDay()).isNull();
            assertThat(d.timing()).isNull();
            assertThat(d.confidence()).isNull();
            assertThat(d.slots()).isEmpty();
        });
        assertThat(r.drugs().get(2).days()).isEqualTo(1);
    }

    @Test
    @DisplayName("이름: 보이는 글자가 없거나 문자열이 아니면 약을 빼고, 길면 100자(코드포인트)로 자르며 제어문자를 지운다")
    void nameRules() throws Exception {
        String longName = "가".repeat(120);
        MedicationOcrResponse r = MedicationOcrProjection.project(json("{\"readable\":true,\"drugs\":["
                + "{\"name\":\"\\u200B \"},{\"name\":7},{},\"x\","
                + "{\"name\":\"" + longName + "\"},{\"name\":\"타이\\u0000레놀\"}]}"));

        assertThat(r.drugs()).extracting(MedicationOcrResponse.Drug::name)
                .containsExactly("가".repeat(100), "타이레놀");
    }

    @Test
    @DisplayName("약은 최대 20개, 안내는 50자로 자른다")
    void caps() throws Exception {
        StringBuilder drugs = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            drugs.append(i == 0 ? "" : ",").append("{\"name\":\"d").append(i).append("\",\"timing\":\"")
                    .append("식".repeat(60)).append("\"}");
        }
        MedicationOcrResponse r = MedicationOcrProjection.project(json("{\"readable\":true,\"drugs\":[" + drugs + "]}"));

        assertThat(r.drugs()).hasSize(MedicationOcrProjection.MAX_DRUGS);
        assertThat(r.drugs().get(0).timing()).hasSize(MedicationOcrProjection.MAX_TIMING_CHARS);
    }

    @Test
    @DisplayName("약 이름 대조 칸: AI가 주면 그대로 옮긴다")
    void projectsNameCheckFields() throws Exception {
        MedicationOcrResponse r = MedicationOcrProjection.project(json("""
                {"readable":true,"drugs":[
                  {"name":"슈다페드정","inList":true,"suggestions":[]},
                  {"name":"슈다페트정","inList":false,"suggestions":["슈다페드정","슈다페드캡슐"]}]}"""));

        assertThat(r.drugs().get(0).inList()).isTrue();
        assertThat(r.drugs().get(0).suggestions()).isEmpty();
        assertThat(r.drugs().get(1).inList()).isFalse();
        assertThat(r.drugs().get(1).suggestions()).containsExactly("슈다페드정", "슈다페드캡슐");
    }

    @Test
    @DisplayName("약 이름 대조 칸: 없거나 null·형식이 다르면 inList=null, suggestions=[] (옛 AI 호환)")
    void nameCheckFieldsMissingOrMalformed() throws Exception {
        MedicationOcrResponse r = MedicationOcrProjection.project(json("""
                {"readable":true,"drugs":[
                  {"name":"a"},
                  {"name":"b","inList":null,"suggestions":null},
                  {"name":"c","inList":"true","suggestions":"슈다페드정"},
                  {"name":"d","inList":1,"suggestions":{"0":"x"}}]}"""));

        assertThat(r.drugs()).hasSize(4).allSatisfy(d -> {
            assertThat(d.inList()).isNull();
            assertThat(d.suggestions()).isEmpty();
        });
    }

    @Test
    @DisplayName("제안: 정확히 100자는 남긴다(경계)")
    void suggestionAtLimitKept() throws Exception {
        String atLimit = "다".repeat(100);
        MedicationOcrResponse r = MedicationOcrProjection.project(json("{\"readable\":true,\"drugs\":[{\"name\":\"a\","
                + "\"inList\":false,\"suggestions\":[\"" + atLimit + "\",\"" + atLimit + "다\"]}]}"));

        assertThat(r.drugs().get(0).suggestions()).containsExactly(atLimit);
    }

    @Test
    @DisplayName("제안은 inList=false일 때만: true·null·칸 없음·형식 다름이면 AI가 제안을 보내도 빈 배열")
    void suggestionsOnlyWhenNotInList() throws Exception {
        MedicationOcrResponse r = MedicationOcrProjection.project(json("""
                {"readable":true,"drugs":[
                  {"name":"a","inList":true,"suggestions":["x"]},
                  {"name":"b","inList":null,"suggestions":["x"]},
                  {"name":"c","suggestions":["x"]},
                  {"name":"d","inList":"false","suggestions":["x"]},
                  {"name":"e","inList":false,"suggestions":["x"]}]}"""));

        assertThat(r.drugs()).extracting(MedicationOcrResponse.Drug::suggestions)
                .containsExactly(java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of("x"));
    }

    @Test
    @DisplayName("제안: 빈 값·문자열 아님·제어문자 정리, 중복은 한 번, 100자 넘는 것은 자르지 않고 빼며 최대 3개")
    void suggestionsFiltered() throws Exception {
        String longName = "나".repeat(120);
        MedicationOcrResponse r = MedicationOcrProjection.project(json("{\"readable\":true,\"drugs\":[{\"name\":\"a\",\"inList\":false,"
                + "\"suggestions\":[\"\",\"\\u200B \",7,null,\"타이\\u0000레놀\",\"타이레놀\",\"" + longName + "\",\"게보린\",\"펜잘\"]}]}"));

        assertThat(r.drugs().get(0).suggestions())
                .hasSize(MedicationOcrProjection.MAX_SUGGESTIONS)
                .containsExactly("타이레놀", "게보린", "펜잘");
    }
}

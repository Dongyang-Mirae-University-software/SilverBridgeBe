package kr.silverbridge.main.domain.checkin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.checkin.entity.CheckinEmotion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class CheckinAiProjectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("생활 신호 영문 코드가 V65 신호별 CHECK 허용 목록 안에 있다")
    void signalCodesMatchCheck() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V65__create_checkin_tables.sql"));
        CheckinAiProjection.SIGNAL_CODES.forEach((name, codes) -> {
            Matcher m = Pattern.compile("chk_checkin_result_" + name + " CHECK \\(([^\\n]*)\\)").matcher(sql);
            assertThat(m.find()).as(name).isTrue();
            codes.values().forEach(code -> assertThat(m.group(1)).as(name + " " + code).contains("'" + code + "'"));
        });
    }

    @Test
    @DisplayName("모르는 정서는 판단 보류, 모르는 신호 값·문자열이 아닌 값은 null(모름)")
    void unknownValues() throws Exception {
        var finish = CheckinAiProjection.finish(MAPPER.readTree("""
                {"emotion":"분노","signals":{"sleep":"아주 좋음","meal":1,"mood":"좋음"},"summary":123}
                """));
        assertThat(finish.emotion()).isEqualTo(CheckinEmotion.UNDECIDED);
        assertThat(finish.signal("sleep")).isNull();
        assertThat(finish.signal("meal")).isNull();
        assertThat(finish.signal("mood")).isEqualTo("GOOD");
        assertThat(finish.summary()).isNull();
        assertThat(finish.evidence()).isEmpty();
    }

    @Test
    @DisplayName("요약의 제어문자를 지우고 500자로 자른다")
    void summarySanitized() throws Exception {
        String longText = "가".repeat(600);
        var finish = CheckinAiProjection.finish(MAPPER.readTree(
                "{\"emotion\":\"기쁨\",\"summary\":\"" + longText + "\\u0007\"}"));
        assertThat(finish.emotion()).isEqualTo(CheckinEmotion.JOY);
        assertThat(finish.summary()).hasSize(500).doesNotContain("\u0007");
    }

    @Test
    @DisplayName("턴 응답에 읽어 드릴 답이 없으면 null(호출부가 503)")
    void turnWithoutReply() throws Exception {
        assertThat(CheckinAiProjection.turn(MAPPER.readTree("{\"transcript\":\"네\",\"reply\":\"\"}"))).isNull();
        assertThat(CheckinAiProjection.turn(MAPPER.readTree("{\"transcript\":\"네\"}"))).isNull();
    }
}

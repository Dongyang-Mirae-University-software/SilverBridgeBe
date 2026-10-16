package kr.silverbridge.main.domain.anomaly.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.anomaly.dto.AnomalySignal;
import kr.silverbridge.main.global.enums.DetectedType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 종류별 결과 {@code results[]}를 읽어 동시 위험을 각각 신호로 만드는지 고정한다(2026-10-16).
 * AI는 대표(최상위)를 화재 > 흉기 > 낙상 순으로 고르므로 대표만 읽으면 낙상이 빠진다.
 */
class AnomalySignalParserMultiDangerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnomalySignalParser parser = new AnomalySignalParser();

    private List<AnomalySignal> all(String json) throws Exception {
        return parser.parseAll(objectMapper.readTree(json));
    }

    private static String message(String top, String results) {
        return "{\"type\":\"latest_analysis\",\"sessionId\":\"ward_a9cC5f_k3m\",\"data\":{" + top
                + ",\"analyzedAt\":\"2026-10-16T01:02:03.000000\",\"results\":[" + results + "]}}";
    }

    @Test
    @DisplayName("화재+흉기 동시 위험이면 둘 다 신호로 만든다")
    void 화재_흉기_동시() throws Exception {
        List<AnomalySignal> signals = all(message(
                "\"detectedType\":\"fire\",\"confidence\":0.9,\"danger\":true",
                "{\"detectedType\":\"fire\",\"confidence\":0.9,\"danger\":true},"
                        + "{\"detectedType\":\"knife\",\"confidence\":0.7,\"danger\":true},"
                        + "{\"detectedType\":\"fall\",\"confidence\":0.0,\"danger\":false}"));

        assertThat(signals).extracting(AnomalySignal::detectedType)
                .containsExactlyInAnyOrder(DetectedType.FIRE, DetectedType.WEAPON);
        assertThat(signals).allMatch(AnomalySignal::danger);
        assertThat(signals).allMatch(s -> s.analyzedAt() != null);
    }

    @Test
    @DisplayName("흉기+낙상 동시 위험이면 대표(흉기)에 가려진 낙상도 신호로 만든다")
    void 흉기_낙상_동시() throws Exception {
        List<AnomalySignal> signals = all(message(
                "\"detectedType\":\"knife\",\"confidence\":0.8,\"danger\":true",
                "{\"detectedType\":\"knife\",\"confidence\":0.8,\"danger\":true},"
                        + "{\"detectedType\":\"fall\",\"confidence\":0.75,\"danger\":true}"));

        assertThat(signals).extracting(AnomalySignal::detectedType)
                .containsExactlyInAnyOrder(DetectedType.WEAPON, DetectedType.FALL);
    }

    @Test
    @DisplayName("대표가 위험 목록에 있으면 같은 종류를 두 번 만들지 않는다 / 연기는 화재로 합쳐 신뢰도 높은 쪽을 쓴다")
    void 같은_종류는_한번() throws Exception {
        List<AnomalySignal> signals = all(message(
                "\"detectedType\":\"fire\",\"confidence\":0.9,\"danger\":true",
                "{\"detectedType\":\"fire\",\"confidence\":0.9,\"danger\":true},"
                        + "{\"detectedType\":\"smoke\",\"confidence\":0.95,\"danger\":true}"));

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).detectedType()).isEqualTo(DetectedType.FIRE);
        assertThat(signals.get(0).confidence()).isEqualTo(0.95);
    }

    @Test
    @DisplayName("위험이 없으면 대표 1건만 돌려준다(CONFIDENCE 폴백·불일치 경고가 대표를 본다)")
    void 위험없으면_대표만() throws Exception {
        List<AnomalySignal> signals = all(message(
                "\"detectedType\":\"fire\",\"confidence\":0.7,\"danger\":false",
                "{\"detectedType\":\"fire\",\"confidence\":0.7,\"danger\":false},"
                        + "{\"detectedType\":\"fall\",\"confidence\":0.0,\"danger\":false}"));

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).danger()).isFalse();
        assertThat(signals.get(0).detectedType()).isEqualTo(DetectedType.FIRE);
    }

    @Test
    @DisplayName("results가 없는 옛·fallback 페이로드는 기존처럼 대표 1건이다")
    void results없으면_대표() throws Exception {
        List<AnomalySignal> signals = all(
                "{\"type\":\"latest_analysis\",\"sessionId\":\"s1\",\"data\":{\"detectedType\":\"fire\","
                        + "\"confidence\":0.9,\"danger\":true}}");

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).detectedType()).isEqualTo(DetectedType.FIRE);
    }

    @Test
    @DisplayName("danger=true인데 모르는 종류는 신호로 만들지 않는다(판정을 바꾸지 않는다)")
    void 모르는_종류는_버림() throws Exception {
        List<AnomalySignal> signals = all(message(
                "\"detectedType\":\"normal\",\"confidence\":0.0,\"danger\":false",
                "{\"detectedType\":\"gun\",\"confidence\":0.9,\"danger\":true}"));

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).detectedType()).isEqualTo(DetectedType.NORMAL);
        assertThat(signals.get(0).danger()).isFalse();
    }

    @Test
    @DisplayName("형식이 어긋난 메시지는 빈 목록이다")
    void 형식불일치() throws Exception {
        assertThat(all("{\"type\":\"latest_analysis\",\"data\":{}}")).isEmpty();
    }

    @Test
    @DisplayName("danger 필드가 없으면 false로 처리하되 파싱은 실패하지 않는다")
    void danger필드_없음() throws Exception {
        List<AnomalySignal> signals = all(
                "{\"type\":\"latest_analysis\",\"sessionId\":\"s2\",\"data\":{\"detectedType\":\"fire\",\"confidence\":0.9}}");

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).danger()).isFalse();
    }
}

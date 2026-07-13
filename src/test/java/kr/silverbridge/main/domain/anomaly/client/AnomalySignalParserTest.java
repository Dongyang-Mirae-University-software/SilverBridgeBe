package kr.silverbridge.main.domain.anomaly.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.anomaly.dto.AnomalySignal;
import kr.silverbridge.main.global.enums.DetectedType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AnomalySignalParser 단위 테스트.
 *
 * AI latest_analysis 페이로드는 정상형과 캐시 미스 fallback형(analyzedAt·detections 없음) 두 가지로 오므로,
 * 둘 다 깨지지 않고 파싱되는지 고정한다.
 */
class AnomalySignalParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnomalySignalParser parser = new AnomalySignalParser();

    private Optional<AnomalySignal> parse(String json) throws Exception {
        return parser.parse(objectMapper.readTree(json));
    }

    @Test
    @DisplayName("정상 페이로드를 파싱한다 (analyzedAt은 naive UTC → UTC 오프셋 부여)")
    void parsesFullPayload() throws Exception {
        Optional<AnomalySignal> parsed = parse("""
                {"type":"latest_analysis","sessionId":"ward_a9cC5f_k3m",
                 "data":{"detectedType":"fire","confidence":0.8412,"danger":true,
                         "detections":[{"detectedType":"fire","confidence":0.8412}],
                         "analyzedAt":"2026-05-30T10:20:22.939247"}}
                """);

        assertThat(parsed).isPresent();
        AnomalySignal signal = parsed.get();
        assertThat(signal.sessionId()).isEqualTo("ward_a9cC5f_k3m");
        assertThat(signal.detectedType()).isEqualTo(DetectedType.FIRE);
        assertThat(signal.confidence()).isEqualTo(0.8412);
        assertThat(signal.danger()).isTrue();
        assertThat(signal.analyzedAt())
                .isEqualTo(OffsetDateTime.of(2026, 5, 30, 10, 20, 22, 939_247_000, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("캐시 미스 fallback 페이로드(analyzedAt·detections 없음)도 파싱하고 analyzedAt은 null로 둔다")
    void parsesFallbackPayload() throws Exception {
        Optional<AnomalySignal> parsed = parse("""
                {"type":"latest_analysis","sessionId":"ward_a9cC5f_k3m",
                 "data":{"detectedType":"smoke","confidence":0.42,"danger":false}}
                """);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().detectedType()).isEqualTo(DetectedType.SMOKE);
        assertThat(parsed.get().analyzedAt()).isNull();
    }

    @Test
    @DisplayName("모르는 detectedType은 UNKNOWN(무시 대상)으로 떨어뜨린다")
    void unknownDetectedType() throws Exception {
        Optional<AnomalySignal> parsed = parse("""
                {"type":"latest_analysis","sessionId":"s1","data":{"detectedType":"flood","confidence":0.9,"danger":true}}
                """);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().detectedType()).isEqualTo(DetectedType.UNKNOWN);
    }

    @Test
    @DisplayName("sessionId·data가 없는 형식 이상 메시지는 버린다")
    void malformedPayload_empty() throws Exception {
        assertThat(parse("{\"type\":\"latest_analysis\"}")).isEmpty();
        assertThat(parse("{\"type\":\"latest_analysis\",\"sessionId\":\"s1\"}")).isEmpty();
    }
}

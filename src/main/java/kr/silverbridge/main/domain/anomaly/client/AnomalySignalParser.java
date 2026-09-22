package kr.silverbridge.main.domain.anomaly.client;

import com.fasterxml.jackson.databind.JsonNode;
import kr.silverbridge.main.domain.anomaly.dto.AnomalySignal;
import kr.silverbridge.main.global.enums.DetectedType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * AI {@code latest_analysis} 메시지(JSON)를 {@link AnomalySignal}로 변환한다.
 *
 * <p>AI 페이로드는 두 형태로 온다(AI 문서 §6-3):</p>
 * <ul>
 *   <li>정상: {@code data = {detectedType, confidence, danger, detections[], analyzedAt}}</li>
 *   <li>캐시 미스 fallback: {@code data = {detectedType, confidence, danger}} — <b>analyzedAt·detections 없음</b></li>
 * </ul>
 *
 * <p>따라서 {@code analyzedAt}은 없거나 파싱 실패할 수 있고, 그 경우 {@code null}로 둔다(수신 시각으로 몰래
 * 채우지 않는다 — 이력에서 "AI 분석 시각"과 "수신 시각"을 구분하기 위함).</p>
 */
@Slf4j
@Component
public class AnomalySignalParser {

    /**
     * @param root {@code {"type":"latest_analysis","sessionId":"...","data":{...}}} 전체 메시지
     * @return 파싱된 신호. sessionId·data가 없는 등 형식이 어긋나면 {@code Optional.empty()}
     */
    public Optional<AnomalySignal> parse(JsonNode root) {
        String sessionId = root.path("sessionId").asText(null);
        JsonNode data = root.path("data");
        if (sessionId == null || sessionId.isBlank() || data.isMissingNode() || !data.isObject()) {
            log.debug("[ANOMALY] latest_analysis 형식 불일치 — 무시: {}", root);
            return Optional.empty();
        }

        DetectedType detectedType = DetectedType.fromAi(data.path("detectedType").asText(null));
        double confidence = normalizeConfidence(sessionId, data.path("confidence").asDouble(0.0));
        boolean danger = data.path("danger").asBoolean(false);

        return Optional.of(new AnomalySignal(
                sessionId, detectedType, confidence, danger, parseAnalyzedAt(data.path("analyzedAt").asText(null))));
    }

    /**
     * confidence는 AI 계약상 0.0~1.0이다. 범위를 벗어나면(퍼센트로 87을 보내는 형식 변경, 음수, NaN) 0~1로 잘라 넣고
     * WARN을 남긴다 - 관리자 화면의 평균 AI 신뢰도가 "8700%"처럼 깨지지 않게 하기 위해서다.
     *
     * <p><b>신호를 버리지 않는다</b>: 버리면 그 사이 화재 알림이 빠진다. 자르기는 판정도 바꾸지 않는다
     * (87 → 1.0은 여전히 임계 이상, 음수·NaN → 0.0은 기존처럼 임계 미달).</p>
     */
    private double normalizeConfidence(String sessionId, double raw) {
        if (raw >= 0.0 && raw <= 1.0) {
            return raw;
        }
        double clamped = Double.isNaN(raw) ? 0.0 : Math.max(0.0, Math.min(1.0, raw));
        log.warn("[ANOMALY-CONFIDENCE-OUT-OF-RANGE] confidence가 0~1 밖이라 {}로 보정: sessionId={}, raw={}",
                clamped, sessionId, raw);
        return clamped;
    }

    /** AI {@code analyzedAt}은 naive UTC(예 {@code 2026-05-30T10:20:22.939247}) — UTC 오프셋을 부여한다. */
    private OffsetDateTime parseAnalyzedAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;   // fallback 페이로드 — 분석 시각 불명(수신 시각은 createdAt에 남는다)
        }
        try {
            return LocalDateTime.parse(raw).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            log.warn("[ANOMALY] analyzedAt 파싱 실패 — 분석 시각 없이 기록: raw={}", raw);
            return null;
        }
    }
}

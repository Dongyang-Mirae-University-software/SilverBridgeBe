package kr.silverbridge.main.domain.anomaly.client;

import com.fasterxml.jackson.databind.JsonNode;
import kr.silverbridge.main.domain.anomaly.dto.AnomalySignal;
import kr.silverbridge.main.global.enums.DetectedType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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

    /** 같은 세션·같은 종류의 진단 로그를 이 간격으로만 남긴다 - AI는 프레임마다 보내 스로틀이 없으면 폭주한다. */
    private static final Duration DIAG_WARN_INTERVAL = Duration.ofMinutes(1);
    private static final int RAW_TYPE_MAX_LENGTH = 32;

    /** (로그 종류:세션) → 마지막 경고 시각. 등록 카메라 세션만 들어와 카메라 대수만큼만 쌓인다. */
    private final Map<String, Instant> lastDiagWarnAt = new ConcurrentHashMap<>();

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
        if (data.path("danger").isMissingNode()) {
            warnThrottled("ANOMALY-DANGER-FIELD-MISSING", sessionId,
                    "danger 필드가 없어 false로 처리 - AI 응답 형식 변경 의심: sessionId={}", sessionId);
        }
        warnIfUnknownDanger(sessionId, danger, detectedType, data.path("detectedType").asText(null));

        return Optional.of(new AnomalySignal(
                sessionId, detectedType, confidence, danger, parseAnalyzedAt(data.path("analyzedAt").asText(null))));
    }

    /**
     * 메시지 1건에서 <b>처리할 신호 전부</b>를 꺼낸다(2026-10-16).
     *
     * <p>AI는 종류별 결과 {@code results[]}를 함께 보내고, 최상위 {@code detectedType/confidence/danger}는
     * 그중 대표 1건(화재 &gt; 흉기 &gt; 낙상)이다. 대표만 읽으면 흉기와 낙상이 동시에 위험일 때 낙상이 빠진다.
     * 그래서 {@code results[]} 중 <b>danger=true이고 감지 대상인 종류를 각각</b> 신호로 만든다.</p>
     *
     * <ul>
     *   <li>같은 종류(연기→화재 포함)가 여럿이면 신뢰도가 가장 높은 1건만 쓴다.</li>
     *   <li>대표 신호는 위험 목록에 없어도 포함한다 - CONFIDENCE 폴백 판정과 danger 불일치 경고가 대표를 보기 때문이다.
     *       대표가 위험 목록에 있는 종류면 중복으로 더하지 않는다.</li>
     *   <li>{@code results[]}가 없는 옛·fallback 페이로드는 대표 1건만 돌려준다(기존 동작).</li>
     * </ul>
     */
    public List<AnomalySignal> parseAll(JsonNode root) {
        return parse(root).map(base -> expand(root, base)).orElse(List.of());
    }

    /** {@link #parse}로 얻은 대표 신호를 종류별 위험 신호 목록으로 넓힌다(대표를 이미 파싱한 호출자용). */
    public List<AnomalySignal> expand(JsonNode root, AnomalySignal base) {
        JsonNode results = root.path("data").path("results");
        if (!results.isArray() || results.isEmpty()) {
            return List.of(base);
        }

        Map<DetectedType, AnomalySignal> dangers = new LinkedHashMap<>();
        for (JsonNode item : results) {
            if (!item.isObject() || !item.path("danger").asBoolean(false)) {
                continue;
            }
            String rawType = item.path("detectedType").asText(null);
            DetectedType type = DetectedType.fromAi(rawType);
            if (!type.isDetectable()) {
                warnIfUnknownDanger(base.sessionId(), true, type, rawType);
                continue;
            }
            double confidence = normalizeConfidence(base.sessionId(), item.path("confidence").asDouble(0.0));
            AnomalySignal candidate = new AnomalySignal(base.sessionId(), type, confidence, true, base.analyzedAt());
            dangers.merge(type, candidate, (a, b) -> a.confidence() >= b.confidence() ? a : b);
        }

        List<AnomalySignal> signals = new ArrayList<>(dangers.values());
        if (!dangers.containsKey(base.detectedType())) {
            signals.add(base);
        }
        return signals;
    }

    // danger=true인데 감지 대상이 아닌 종류(모르는 값·normal·unknown)는 판정에서 버려진다 - 버린 사실을 남긴다.
    private void warnIfUnknownDanger(String sessionId, boolean danger, DetectedType type, String rawType) {
        if (!danger || type.isDetectable()) {
            return;
        }
        warnThrottled("ANOMALY-UNKNOWN-TYPE-DANGER", sessionId,
                "danger=true인데 감지 대상이 아닌 종류라 버림: sessionId={}, rawType={}", sessionId, sanitizeRaw(rawType));
    }

    private void warnThrottled(String tag, String sessionId, String format, Object... args) {
        String key = tag + ":" + sessionId;
        Instant now = Instant.now();
        Instant last = lastDiagWarnAt.get(key);
        if (last != null && Duration.between(last, now).compareTo(DIAG_WARN_INTERVAL) < 0) {
            return;
        }
        lastDiagWarnAt.put(key, now);
        log.warn("[" + tag + "] " + format, args);
    }

    /** AI 원본 종류 문자열은 외부 입력이라 영숫자·_·-만 남기고 길이를 자른다(로그 주입·폭주 방지). */
    private static String sanitizeRaw(String raw) {
        if (raw == null) {
            return "null";
        }
        String cleaned = raw.replaceAll("[^A-Za-z0-9_-]", "");
        return cleaned.length() > RAW_TYPE_MAX_LENGTH ? cleaned.substring(0, RAW_TYPE_MAX_LENGTH) : cleaned;
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

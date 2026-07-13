package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties.TriggerMode;
import kr.silverbridge.main.domain.anomaly.dto.AnomalySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 신호가 "이상감지"인지 판정한다.
 *
 * <p><b>DANGER 모드(기본)</b> — AI가 채운 {@code danger} 플래그만 신뢰한다. 위험 판정 책임은 AI 서버에 있고
 * 백엔드는 전달·이력을 맡는다는 역할 분리(AI 담당자 협의)를 코드로 굳힌 것이다.</p>
 *
 * <p><b>CONFIDENCE 모드(폴백)</b> — AI의 danger 정식화 배포가 늦어지면(현 코드상 라이브 경로의 danger는
 * 항상 false) 이력이 조용히 0건이 된다. 그 공백을 임시로 메우기 위한 백엔드 자체 임계 판정이다.</p>
 *
 * <p>두 모드 모두 {@code normal}·{@code unknown}과 라이브 미탑재 종류는 무시한다
 * ({@link kr.silverbridge.main.global.enums.DetectedType#isDetectable()}).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnomalyJudge {

    /** danger 불일치 경고를 세션당 이 간격으로만 남긴다 — 매 프레임 broadcast라 스로틀 없이는 로그가 폭주한다. */
    private static final Duration MISMATCH_WARN_INTERVAL = Duration.ofMinutes(1);

    private final AnomalyProperties properties;

    /** 세션별 마지막 danger-불일치 경고 시각 (로그 스로틀용). */
    private final Map<String, Instant> lastMismatchWarnAt = new ConcurrentHashMap<>();

    /** 이 신호를 이상감지로 인정할지. */
    public boolean isAnomaly(AnomalySignal signal) {
        if (!signal.detectedType().isDetectable()) {
            return false;   // normal·unknown·(미탑재 종류)
        }

        if (properties.getTriggerMode() == TriggerMode.CONFIDENCE) {
            return signal.confidence() >= properties.getConfidenceThreshold();
        }

        // DANGER 모드
        if (!signal.danger()) {
            warnIfDangerLooksStale(signal);
            return false;
        }
        return true;
    }

    /**
     * "감지 종류는 fire/smoke이고 신뢰도도 충분히 높은데 danger만 false"가 계속되면 AI의 danger 정식화가
     * 배포되지 않았을 가능성이 크다. 이 경우 이상감지가 **한 건도 적재되지 않는데 아무 에러도 나지 않으므로**,
     * 조용한 침묵 대신 로그로 드러낸다(운영자가 CONFIDENCE 폴백 전환 여부를 판단할 근거).
     */
    private void warnIfDangerLooksStale(AnomalySignal signal) {
        if (signal.confidence() < properties.getConfidenceThreshold()) {
            return;   // 신뢰도 자체가 낮으면 danger=false가 정상
        }

        Instant now = Instant.now();
        Instant lastWarn = lastMismatchWarnAt.get(signal.sessionId());
        if (lastWarn != null && Duration.between(lastWarn, now).compareTo(MISMATCH_WARN_INTERVAL) < 0) {
            return;
        }
        lastMismatchWarnAt.put(signal.sessionId(), now);

        log.warn("[ANOMALY-DANGER-MISMATCH] 고신뢰 감지인데 danger=false — AI danger 판정 미배포 의심"
                        + "(이력 0건 가능, 필요 시 anomaly.trigger-mode=CONFIDENCE 폴백): "
                        + "sessionId={}, detectedType={}, confidence={}",
                signal.sessionId(), signal.detectedType(), signal.confidence());
    }
}

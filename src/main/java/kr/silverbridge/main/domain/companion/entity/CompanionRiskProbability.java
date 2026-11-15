package kr.silverbridge.main.domain.companion.entity;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 턴 위험 확률과 그 눈금(2026-11-15). AI 턴 응답의 {@code riskModel}이 눈금을 알린다: 없음·{@code "v9"} = 기본 모델(v9),
 * {@code "v9+<어댑터>"} = 위험 판단 어댑터(LoRA). 두 눈금은 같은 숫자라도 뜻이 달라 여러 날 누적 기준을 따로 둔다
 * (v9 0.97 · 어댑터 0.3). 로그·알림 문구에 싣지 않는다.
 *
 * @param value 0~1 확률(소수 넷째 자리 내림)
 * @param lora  위험 판단 어댑터 눈금이면 참
 */
public record CompanionRiskProbability(BigDecimal value, boolean lora) {

    public CompanionRiskProbability {
        Objects.requireNonNull(value, "value");
    }

    /** v9 눈금 확률 (AI #296 전 응답·{@code riskModel} 없음). null 이면 null. */
    public static CompanionRiskProbability v9(BigDecimal value) {
        return value == null ? null : new CompanionRiskProbability(value, false);
    }

    /** 위험 판단 어댑터 눈금 확률. null 이면 null. */
    public static CompanionRiskProbability lora(BigDecimal value) {
        return value == null ? null : new CompanionRiskProbability(value, true);
    }

    /** 이 눈금의 기준 이상인가. */
    public boolean atOrAbove(BigDecimal v9Threshold, BigDecimal loraThreshold) {
        return value.compareTo(lora ? loraThreshold : v9Threshold) >= 0;
    }
}

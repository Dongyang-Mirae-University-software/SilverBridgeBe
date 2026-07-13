package kr.silverbridge.main.domain.anomaly.dto;

import kr.silverbridge.main.global.enums.DetectedType;

import java.time.OffsetDateTime;

/**
 * AI {@code latest_analysis} 신호를 백엔드 도메인 값으로 옮긴 것.
 *
 * @param sessionId    AI 세션 = {@code cameras.session_id}
 * @param detectedType 감지 종류(fire/smoke/normal/unknown → enum)
 * @param confidence   최고 신뢰도
 * @param danger       AI 위험 판정 플래그. <b>DANGER 모드의 유일한 판정 근거</b>
 * @param analyzedAt   AI 분석 시각(naive UTC → UTC 오프셋 부여). fallback 페이로드엔 없어서 <b>null 가능</b>
 */
public record AnomalySignal(
        String sessionId,
        DetectedType detectedType,
        double confidence,
        boolean danger,
        OffsetDateTime analyzedAt
) {}

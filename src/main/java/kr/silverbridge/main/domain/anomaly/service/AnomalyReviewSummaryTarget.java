package kr.silverbridge.main.domain.anomaly.service;

import java.time.LocalDate;

/**
 * 선점이 끝난 하루 1회 요약 1건.
 *
 * @param pendingCount 아직 응답하지 않은 상황 수. 상황 단위이지 감지 이력 건수가 아니다
 */
public record AnomalyReviewSummaryTarget(
        String guardianId,
        LocalDate summaryDate,
        int pendingCount) {
}

package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.global.enums.DetectedType;

import java.time.OffsetDateTime;

/**
 * 선점이 끝난 건별 재촉 1건. 발송은 {@link AnomalyReviewReminderService}가 트랜잭션 커밋 뒤에 한다.
 *
 * @param cameraLabel 감지 위치. 카메라가 삭제됐으면 null이라 문구에서 폴백한다
 */
public record AnomalyReviewReminderTarget(
        String guardianId,
        Long incidentId,
        String wardId,
        String wardName,
        String cameraLabel,
        DetectedType detectedType,
        OffsetDateTime startedAt) {
}

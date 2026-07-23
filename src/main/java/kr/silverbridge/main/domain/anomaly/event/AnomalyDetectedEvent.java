package kr.silverbridge.main.domain.anomaly.event;

import kr.silverbridge.main.global.enums.DetectedType;

import java.time.OffsetDateTime;

/**
 * 이상감지 이력이 적재됐을 때 발행되는 이벤트.
 *
 * <p>{@code AnomalyNotificationListener}가 커밋 후(AFTER_COMMIT) 보호자·본인에게 알림을 발송한다.
 * 발송 실패가 이력 저장을 롤백시키지 않도록 이벤트로 분리한다(SOS와 동일 패턴).</p>
 *
 * <p>알림 문구에 필요한 값(피보호자 이름·카메라 위치)을 이벤트에 실어 보낸다 — 리스너는 비동기라 트랜잭션 밖에서
 * 돌고, 문구 하나를 위해 다시 DB를 조회할 이유가 없다.</p>
 *
 * @param anomalyEventId 적재된 {@code anomaly_event} 행 ID (클라이언트 상관관계용)
 * @param wardId         카메라 소유 피보호자 ID
 * @param wardName       피보호자 이름 (비어 있으면 발행 측에서 "보호 대상자"로 폴백)
 * @param sessionId      감지된 카메라 SessionID
 * @param cameraLabel    카메라 설치 위치(방 이름 — 거실·안방 등)
 * @param detectedType   감지 종류(FIRE·SMOKE)
 * @param detectedAt     AI 분석 시각({@code analyzedAt}). AI 캐시 미스 fallback 페이로드엔 없어 <b>null 가능</b> —
 *                       이력({@code anomaly_event.detected_at})과 동일하게 수신 시각을 몰래 채우지 않는다.
 *                       알림 문구에 빈 칸이 나가지 않게 하는 대체는 리스너가 표시 단계에서만 한다.
 */
public record AnomalyDetectedEvent(
        Long anomalyEventId,
        String wardId,
        String wardName,
        String sessionId,
        String cameraLabel,
        DetectedType detectedType,
        OffsetDateTime detectedAt
) {}

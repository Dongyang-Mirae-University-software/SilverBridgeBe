package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.dto.AnomalySignal;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyEvent;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyEventRepository;
import kr.silverbridge.main.domain.camera.service.CameraService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * AI 이상감지 신호를 판정해 이력으로 남긴다 (1단계 — 수신·판정·이력).
 *
 * <p>흐름: <b>판정({@link AnomalyJudge}) → 쿨다운({@link AnomalyEventCooldown}) → sessionId→wardId 매핑
 * → {@code anomaly_events} 적재</b>.</p>
 *
 * <p>미등록 세션은 애초에 구독하지 않으므로(구독 필터가 1차 방어) 여기까지 오는 경우는 드물다 —
 * 세션 등록 직후·삭제 직후의 경합 정도. 그때는 WARN만 남기고 버린다(소유자 없는 이력은 만들지 않는다).</p>
 *
 * <p><b>2단계 인계 지점</b>: 이력 저장 직후가 알림 이벤트({@code AnomalyDetectedEvent}) 발행 자리다.
 * SOS와 동일하게 AFTER_COMMIT 리스너가 보호자에게 발송하는 구조를 예정한다(이번 단계에서는 발행하지 않음).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyDetectionService {

    private final AnomalyJudge judge;
    private final AnomalyEventCooldown cooldown;
    private final CameraService cameraService;
    private final AnomalyEventRepository anomalyEventRepository;

    /**
     * 신호 1건을 처리한다. 이상감지가 아니거나(정상 프레임) 쿨다운·매핑에서 걸리면 아무것도 남기지 않는다.
     *
     * @return 적재된 이력. 판정/쿨다운/매핑에서 걸러졌으면 {@code Optional.empty()}
     */
    @Transactional
    public Optional<AnomalyEvent> handle(AnomalySignal signal) {
        if (!judge.isAnomaly(signal)) {
            return Optional.empty();
        }

        if (!cooldown.tryAcquire(signal.sessionId(), signal.detectedType())) {
            log.debug("[ANOMALY] 쿨다운 내 중복 — 이력 스킵: sessionId={}, detectedType={}",
                    signal.sessionId(), signal.detectedType());
            return Optional.empty();
        }

        Optional<String> wardId = cameraService.findWardIdBySessionId(signal.sessionId());
        if (wardId.isEmpty()) {
            log.warn("[ANOMALY] 알 수 없는 세션 — 등록된 카메라가 없어 이력 스킵: sessionId={}, detectedType={}",
                    signal.sessionId(), signal.detectedType());
            return Optional.empty();
        }

        AnomalyEvent event = anomalyEventRepository.save(AnomalyEvent.builder()
                .wardId(wardId.get())
                .sessionId(signal.sessionId())
                .detectedType(signal.detectedType())
                .confidence(signal.confidence())
                .danger(signal.danger())
                .detectedAt(signal.analyzedAt())   // null 가능 — AI fallback 페이로드엔 analyzedAt이 없다
                .build());

        log.info("[ANOMALY] 이상감지 이력 적재: id={}, wardId={}, sessionId={}, detectedType={}, confidence={}, danger={}",
                event.getId(), event.getWardId(), event.getSessionId(),
                event.getDetectedType(), event.getConfidence(), event.isDanger());

        // 2단계: 여기서 AnomalyDetectedEvent 발행 → AFTER_COMMIT 리스너가 보호자에게 FCM(고정)·알림톡/SMS(선택) 발송
        return Optional.of(event);
    }
}

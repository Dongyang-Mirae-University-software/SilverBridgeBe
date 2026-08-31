package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.dto.AnomalySignal;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyEvent;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.event.AnomalyDetectedEvent;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyEventRepository;
import kr.silverbridge.main.domain.camera.dto.CameraOwner;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * AI 이상감지 신호를 판정해 이력으로 남긴다 (1단계 — 수신·판정·이력).
 *
 * <p>흐름: <b>판정({@link AnomalyJudge}) → 쿨다운({@link AnomalyEventCooldown}) → sessionId→wardId 매핑
 * → 상황 편입({@link AnomalyIncidentService}) → {@code anomaly_event} 적재</b>.</p>
 *
 * <p>미등록 세션은 애초에 구독하지 않으므로(구독 필터가 1차 방어) 여기까지 오는 경우는 드물다 —
 * 세션 등록 직후·삭제 직후의 경합 정도. 그때는 WARN만 남기고 버린다(소유자 없는 이력은 만들지 않는다).</p>
 *
 * <p><b>2단계(알림)</b>: 이력 저장 직후 {@link AnomalyDetectedEvent}를 발행한다. 실제 발송은
 * {@code AnomalyNotificationListener}가 커밋 후(AFTER_COMMIT) 담당하므로, 발송이 실패하거나 느려도 이력은
 * 롤백되지 않는다(SOS와 동일 패턴).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyDetectionService {

    /** 피보호자 이름이 비어 있을 때 알림 문구에 쓰는 폴백 — "null님 댁…" 같은 문구를 막는다(SosService와 동일). */
    private static final String FALLBACK_WARD_NAME = "보호 대상자";

    private final AnomalyJudge judge;
    private final AnomalyIncidentService incidentService;
    private final AnomalyEventCooldown cooldown;
    private final CameraService cameraService;
    private final AnomalyEventRepository anomalyEventRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

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

        Optional<CameraOwner> owner = cameraService.findOwnerBySessionId(signal.sessionId());
        if (owner.isEmpty()) {
            log.warn("[ANOMALY] 알 수 없는 세션 — 등록된 카메라가 없어 이력 스킵: sessionId={}, detectedType={}",
                    signal.sessionId(), signal.detectedType());
            return Optional.empty();
        }
        String wardId = owner.get().wardId();

        // 판정·통계의 단위인 "상황"에 편입한다(연속 감지면 승계, 아니면 새로 연다).
        // 묶음 기준은 수신 시각이다 — signal.analyzedAt()은 AI fallback 페이로드에서 null일 수 있다.
        AnomalyIncident incident = incidentService.resolveIncident(
                wardId, signal.sessionId(), signal.detectedType(), OffsetDateTime.now(), signal.confidence());

        AnomalyEvent event = anomalyEventRepository.save(AnomalyEvent.builder()
                .wardId(wardId)
                .sessionId(signal.sessionId())
                .detectedType(signal.detectedType())
                .confidence(signal.confidence())
                .danger(signal.danger())
                .detectedAt(signal.analyzedAt())   // null 가능 — AI fallback 페이로드엔 analyzedAt이 없다
                .incidentId(incident.getId())
                .build());

        log.info("[ANOMALY] 이상감지 이력 적재: id={}, incidentId={}, wardId={}, sessionId={}, detectedType={}, confidence={}, danger={}",
                event.getId(), event.getIncidentId(), event.getWardId(), event.getSessionId(),
                event.getDetectedType(), event.getConfidence(), event.isDanger());

        // 커밋 후 AnomalyNotificationListener가 보호자 전원 + 피보호자 본인에게 발송(FCM 고정 + SMS·알림톡은 선택)
        eventPublisher.publishEvent(new AnomalyDetectedEvent(
                event.getId(), incident.getId(), wardId, wardName(wardId),
                signal.sessionId(), owner.get().label(), signal.detectedType(), signal.analyzedAt()));

        return Optional.of(event);
    }

    // 알림 문구용 피보호자 이름. 사용자 행이 없거나 이름이 비어도 발송은 계속한다(문구만 폴백).
    private String wardName(String wardId) {
        return userRepository.findById(wardId)
                .map(user -> StringUtils.hasText(user.getName()) ? user.getName() : FALLBACK_WARD_NAME)
                .orElse(FALLBACK_WARD_NAME);
    }
}

package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.client.AiClipClient;
import kr.silverbridge.main.domain.anomaly.client.AiClipClient.ClipResult;
import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyClip;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyEvent;
import kr.silverbridge.main.domain.anomaly.event.AnomalyDetectedEvent;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyEventRepository;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipService.StoredClip;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 이상감지 클립 생성 흐름: <b>위험 이력 확인 → 클립 쿨다운 → AI 요청 → 검증 → 디스크 저장 → 행 기록</b>.
 *
 * <p>이력 적재 커밋 뒤 {@code clipExecutor} 스레드에서만 돈다({@code AnomalyClipListener}). 트랜잭션을 걸지 않는다 -
 * AI 응답(최대 25초)을 기다리는 동안 DB 연결을 붙들면 안 된다. 행 기록만 짧은 트랜잭션이다.</p>
 *
 * <p><b>이력·알림과 분리</b>: 여기서 무엇이 실패해도 이력·알림은 이미 끝났거나 별도 스레드다. 예외는 밖으로 내보내지 않는다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyClipCaptureService {

    private final AnomalyProperties properties;
    private final AnomalyEventRepository eventRepository;
    private final AnomalyClipCooldown cooldown;
    private final AiClipClient aiClipClient;
    private final AnomalyClipStorage storage;
    private final AnomalyClipService clipService;

    public void capture(AnomalyDetectedEvent event) {
        if (!properties.getClip().isEnabled()) {
            return;
        }
        // 위험(danger=true)으로 적재된 이력만 - CONFIDENCE 폴백 모드의 danger=false 이력은 클립을 만들지 않는다(2026-10-04 결정)
        Optional<AnomalyEvent> stored = eventRepository.findById(event.anomalyEventId());
        if (stored.isEmpty() || !stored.get().isDanger()) {
            log.debug("[ANOMALY-CLIP] 위험 이력이 아니라 생략: anomalyEventId={}", event.anomalyEventId());
            return;
        }
        if (!cooldown.tryAcquire(event.sessionId(), event.detectedType())) {
            return;
        }

        boolean done = false;
        try {
            done = captureAndStore(event);
        } catch (RuntimeException e) {
            log.error("[ANOMALY-CLIP] 클립 처리 중 오류: sessionId={}, incidentId={}, error={}",
                    event.sessionId(), event.incidentId(), e.getClass().getSimpleName());
        } finally {
            if (!done) {
                cooldown.release(event.sessionId(), event.detectedType());
            }
        }
    }

    /**
     * @return 쿨다운을 유지해야 하면 true(성공했거나, 다시 시도해도 같은 결과인 실패). false면 호출부가 쿨다운을 푼다.
     */
    private boolean captureAndStore(AnomalyDetectedEvent event) {
        if (!storage.hasEnoughSpace()) {
            // 디스크가 부족한 동안 1분마다 다시 요청해도 소용이 없어 쿨다운을 유지한다
            log.error("[ANOMALY-CLIP] 저장 공간 부족 - 클립 생략: sessionId={}, minFreeMb={}",
                    event.sessionId(), properties.getClip().getMinFreeDiskMb());
            return true;
        }

        // 감지 시각이 없으면(AI fallback 페이로드) 보내지 않는다 - 계약상 AI가 요청 수신 시각을 쓴다
        OffsetDateTime requestedAt = OffsetDateTime.now();
        ClipResult result = aiClipClient.requestClip(event.sessionId(), event.detectedAt(), event.detectedType());
        if (!result.isOk()) {
            // 로그는 AiClipClient가 남겼다. 다시 보내도 같은 실패(키·파라미터 결함)면 쿨다운을 유지해 AI를 두드리지 않는다.
            return !result.outcome().isRetryable();
        }

        String fileName;
        try {
            fileName = storage.write(result.body());
        } catch (UncheckedIOException e) {
            log.error("[ANOMALY-CLIP] 클립 파일 저장 실패: sessionId={}, error={}",
                    event.sessionId(), e.getCause().getClass().getSimpleName());
            return false;
        }

        Optional<AnomalyClip> clip;
        try {
            clip = clipService.record(new StoredClip(event.incidentId(), event.wardId(), event.sessionId(), fileName,
                    result.body().length, result.meta(),
                    event.detectedAt() != null ? event.detectedAt() : requestedAt));
        } catch (RuntimeException e) {
            storage.delete(fileName);
            throw e;
        }
        if (clip.isEmpty()) {
            // 상황·카메라가 사라졌거나 상한 도달 - 다시 시도할 이유가 없다
            storage.delete(fileName);
            return true;
        }

        log.info("[ANOMALY-CLIP] 클립 저장: clipId={}, incidentId={}, sessionId={}, sizeBytes={}, status={}",
                clip.get().getId(), event.incidentId(), event.sessionId(), result.body().length, clip.get().getStatus());
        return true;
    }
}

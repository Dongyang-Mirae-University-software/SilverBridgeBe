package kr.silverbridge.main.domain.anomaly.listener;

import kr.silverbridge.main.domain.anomaly.event.AnomalyDetectedEvent;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipCaptureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 이상감지 이력이 커밋되면 영상 클립을 만든다(2026-10-04).
 *
 * <p>알림 리스너({@code AnomalyNotificationListener})와 <b>같은 이벤트를 따로</b> 받는다 - 클립은 AI 응답을 최대 25초
 * 기다리므로 긴급 알림 풀({@code urgentNotificationExecutor})에 태우면 화재 알림이 그 뒤에 줄을 선다. 전용
 * {@code clipExecutor}는 작고 포화 시 폐기한다(클립 하나를 잃는 쪽이 알림·AI 수신을 막는 쪽보다 낫다).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnomalyClipListener {

    private final AnomalyClipCaptureService captureService;

    @Async("clipExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleAnomalyDetected(AnomalyDetectedEvent event) {
        try {
            captureService.capture(event);
        } catch (RuntimeException e) {
            log.error("[ANOMALY-CLIP] 클립 생성 실패: sessionId={}, incidentId={}, error={}",
                    event.sessionId(), event.incidentId(), e.getClass().getSimpleName());
        }
    }
}

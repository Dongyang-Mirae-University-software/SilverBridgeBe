package kr.silverbridge.main.domain.anomaly.listener;

import kr.silverbridge.main.domain.anomaly.service.AnomalyClipService;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipStorage;
import kr.silverbridge.main.domain.camera.event.CameraDeletedEvent;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 탈퇴·카메라 삭제 시 이상감지 클립(행 + 파일)을 지운다(2026-10-04).
 *
 * <p><b>동기 AFTER_COMMIT</b>이다. 탈퇴는 커밋 직후 purge가 회원 행을 지우므로, 비동기로 미루면 CASCADE가 행을 먼저 지워
 * 어떤 파일을 지울지 알 수 없게 된다. 그래서 행 삭제는 {@code REQUIRES_NEW}({@link AnomalyClipService})로 커밋하고, 커밋된 뒤
 * 파일을 지운다(H-1 규칙).</p>
 *
 * <p>예외는 밖으로 내보내지 않는다 - 탈퇴 리스너가 실패를 전파하면 나머지 리스너·purge까지 막힌다(M-S1-1). 남은 행은 CASCADE가,
 * 남은 파일은 청소 스케줄러의 고아 회수가 맡는다(스윕 purge 경로도 같다).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnomalyClipCleanupListener {

    private final AnomalyClipService clipService;
    private final AnomalyClipStorage storage;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleWithdrawn(UserWithdrawnEvent event) {
        try {
            List<String> files = clipService.deleteAllOfWard(event.userId());
            files.forEach(storage::delete);
            if (!files.isEmpty()) {
                log.info("[ANOMALY-CLIP] 탈퇴로 클립 삭제: wardId={}, count={}", event.userId(), files.size());
            }
        } catch (RuntimeException e) {
            log.error("[ANOMALY-CLIP] 탈퇴 클립 정리 실패 - CASCADE·고아 청소가 회수 예정: userId={}, error={}",
                    event.userId(), e.getClass().getSimpleName());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleCameraDeleted(CameraDeletedEvent event) {
        try {
            List<String> files = clipService.deleteAllOfCameras(event.wardId(), event.sessionIds());
            files.forEach(storage::delete);
            if (!files.isEmpty()) {
                log.info("[ANOMALY-CLIP] 카메라 삭제로 클립 삭제: wardId={}, cameras={}, count={}",
                        event.wardId(), event.sessionIds().size(), files.size());
            }
        } catch (RuntimeException e) {
            log.error("[ANOMALY-CLIP] 카메라 클립 정리 실패 - 청소가 회수 예정: wardId={}, error={}",
                    event.wardId(), e.getClass().getSimpleName());
        }
    }
}

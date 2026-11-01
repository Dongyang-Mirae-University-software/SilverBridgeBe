package kr.silverbridge.main.domain.checkin.listener;

import kr.silverbridge.main.domain.checkin.service.CheckinSettingService;
import kr.silverbridge.main.domain.connection.event.ConnectionDisconnectedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 연결이 끝나면 그 보호자가 켜 둔 안부 알림을 끈다(2026-11-01 점검 L-1). 보호자·피보호자 해제, 관리자 강제 해제,
 * 역할 변경·탈퇴 정리 모두 같다. 남은 보호자가 원하면 다시 켠다.
 *
 * <p>동기 AFTER_COMMIT이고 쓰기는 {@code REQUIRES_NEW} 서비스 메서드에 맡긴다({@code SyncAfterCommitListenerPropagationTest} 표).
 * best-effort - 실패해도 연결 해제·알림·탈퇴 흐름을 막지 않는다(예외 클래스명만 로그).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckinConnectionEndedListener {

    private final CheckinSettingService settingService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleDisconnected(ConnectionDisconnectedEvent event) {
        if (event.guardianId() == null || event.wardId() == null) {
            return;
        }
        try {
            int changed = settingService.disablePromptEnabledBy(event.guardianId(), event.wardId());
            if (changed > 0) {
                log.info("[CHECKIN] 연결 종료로 안부 알림 끔: guardianId={}, wardId={}, by={}",
                        event.guardianId(), event.wardId(), event.disconnectedBy());
            }
        } catch (RuntimeException e) {
            log.warn("[CHECKIN] 연결 종료 시 안부 알림 끄기 실패: guardianId={}, wardId={}, error={}",
                    event.guardianId(), event.wardId(), e.getClass().getSimpleName());
        }
    }
}

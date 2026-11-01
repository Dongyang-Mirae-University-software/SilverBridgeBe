package kr.silverbridge.main.domain.checkin.listener;

import kr.silverbridge.main.domain.checkin.event.CheckinGuardianAlertEvent;
import kr.silverbridge.main.domain.checkin.service.CheckinGuardianAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 안부 보호자 알림 발송(커밋 뒤, 비동기). 피보호자의 턴·정리 응답이 FCM 호출을 기다리지 않게 한다.
 *
 * <p>위험은 <b>긴급 executor</b>(SOS·이상감지와 같은 풀 - 일반 알림 포화에 밀리지 않게), 확인 필요는 일반 executor.
 * {@code NotificationExecutorAssignmentTest}가 고정한다. 선점 쓰기는 저장소의 REQUIRES_NEW라 이 리스너는 트랜잭션이 없다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckinGuardianAlertListener {

    private final CheckinGuardianAlertService alertService;

    @Async("urgentNotificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT,
            condition = "#event.kind() == T(kr.silverbridge.main.domain.checkin.event.CheckinGuardianAlertEvent.Kind).RISK")
    public void onRisk(CheckinGuardianAlertEvent event) {
        run(event);
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT,
            condition = "#event.kind() == T(kr.silverbridge.main.domain.checkin.event.CheckinGuardianAlertEvent.Kind).ATTENTION")
    public void onAttention(CheckinGuardianAlertEvent event) {
        run(event);
    }

    private void run(CheckinGuardianAlertEvent event) {
        try {
            switch (event.kind()) {
                case RISK -> alertService.sendRisk(event.wardId(), event.checkDate(), event.slot());
                case ATTENTION -> alertService.sendAttention(event.wardId(), event.checkDate(), event.slot());
            }
        } catch (RuntimeException e) {
            log.warn("[CHECKIN-ALERT-FAILED] 보호자 알림 처리 실패: kind={}, wardId={}, error={}",
                    event.kind(), event.wardId(), e.getClass().getSimpleName());
        }
    }
}

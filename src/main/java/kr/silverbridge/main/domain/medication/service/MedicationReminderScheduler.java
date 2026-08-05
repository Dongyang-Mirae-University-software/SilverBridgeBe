package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.medication.config.MedicationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 복약 알림 발송 스케줄러. 1분마다 "지금 보낼 알림"이 있는지 확인한다.
 *
 * <p>주기가 1분인 이유는 복용 시각이 분 단위이기 때문이다. 같은 알림이 매 분 반복되지 않는 것은
 * {@code medication_reminder_log}의 발송 기록이 보장한다({@link MedicationReminderPlanner}).</p>
 *
 * <p><b>예외를 삼킨다</b> — 한 주기의 실패가 스케줄러 자체를 멈추면 이후 알림이 전부 사라진다.
 * 로그만 남기고 다음 주기에 다시 시도한다({@code WithdrawnUserPurgeScheduler}와 동일한 방침).</p>
 *
 * <p>{@code medication.reminder.enabled=false}면 아무것도 하지 않는다 — 운영 중 문구·빈도 문제가
 * 드러났을 때 배포 없이 즉시 멈추기 위한 킬 스위치다. 등록·체크·조회 기능은 영향을 받지 않는다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MedicationReminderScheduler {

    private final MedicationReminderService reminderService;
    private final MedicationMissedAlertService missedAlertService;
    private final MedicationProperties properties;

    @Scheduled(fixedDelay = 60_000)
    public void sendDueReminders() {
        sendWardReminders();
        sendGuardianMissedAlerts();
    }

    /** 피보호자에게 보내는 복용 시각 알림·재알림(2차). */
    private void sendWardReminders() {
        if (!properties.isEnabled()) {
            return;
        }

        try {
            int first = reminderService.sendFirstReminders();
            if (first > 0) {
                log.info("[MEDICATION-REMINDER] 복약 알림 발송 {}건", first);
            }
        } catch (RuntimeException e) {
            log.error("[MEDICATION-REMINDER] 복약 알림 발송 실패, 다음 주기에 재시도", e);
        }

        try {
            int retry = reminderService.sendRetryReminders();
            if (retry > 0) {
                log.info("[MEDICATION-REMINDER] 복약 재알림 발송 {}건", retry);
            }
        } catch (RuntimeException e) {
            log.error("[MEDICATION-REMINDER] 복약 재알림 발송 실패, 다음 주기에 재시도", e);
        }
    }

    /**
     * 보호자에게 보내는 저녁 미복용 요약(3차).
     *
     * <p>킬 스위치가 <b>피보호자 알림과 별개</b>다 — 보호자 쪽 문구·빈도 문제로 이걸 끄더라도
     * 피보호자의 복용 알림은 계속 나가야 한다. 실제 발송 시각 판정은 Planner가 한다.</p>
     */
    private void sendGuardianMissedAlerts() {
        if (!properties.getMissedAlert().isEnabled()) {
            return;
        }

        try {
            int missed = missedAlertService.sendMissedAlerts();
            if (missed > 0) {
                log.info("[MEDICATION-MISSED] 미복용 요약 발송 {}건", missed);
            }
        } catch (RuntimeException e) {
            log.error("[MEDICATION-MISSED] 미복용 요약 발송 실패, 다음 주기에 재시도", e);
        }
    }
}

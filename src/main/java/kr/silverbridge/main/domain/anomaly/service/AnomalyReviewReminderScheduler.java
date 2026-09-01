package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 판정 미응답 재촉 스케줄러. 5분마다 "지금 보낼 재촉"이 있는지 확인한다.
 *
 * <p>주기가 복약(1분)보다 성긴 이유는 기준이 "상황이 닫히고 1시간 뒤"라 분 단위 정확도가 필요 없기
 * 때문이다. 같은 재촉이 반복되지 않는 것은 {@code anomaly_review_reminder_log}의 선점 기록이 보장한다.</p>
 *
 * <p><b>예외를 삼킨다</b> - 한 주기의 실패가 스케줄러 자체를 멈추면 이후 재촉이 전부 사라진다.
 * 로그만 남기고 다음 주기에 다시 시도한다(복약 스케줄러와 동일한 방침).</p>
 *
 * <p>{@code anomaly.review-reminder.enabled=false}면 아무것도 하지 않는다 - 문구·빈도 문제가 드러났을 때
 * 배포 없이 즉시 멈추기 위한 킬 스위치다. <b>이력 조회·오탐 응답은 영향을 받지 않는다.</b></p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnomalyReviewReminderScheduler {

    private final AnomalyReviewReminderService reminderService;
    private final AnomalyProperties properties;

    @Scheduled(fixedDelay = 300_000)
    public void sendDueReminders() {
        if (!properties.getReviewReminder().isEnabled()) {
            return;
        }

        try {
            int reminders = reminderService.sendReminders();
            if (reminders > 0) {
                log.info("[ANOMALY-REVIEW] 판정 재촉 발송 {}건", reminders);
            }
        } catch (RuntimeException e) {
            log.error("[ANOMALY-REVIEW] 판정 재촉 발송 실패, 다음 주기에 재시도", e);
        }

        try {
            int summaries = reminderService.sendSummaries();
            if (summaries > 0) {
                log.info("[ANOMALY-REVIEW] 미응답 요약 발송 {}건", summaries);
            }
        } catch (RuntimeException e) {
            log.error("[ANOMALY-REVIEW] 미응답 요약 발송 실패, 다음 주기에 재시도", e);
        }
    }
}

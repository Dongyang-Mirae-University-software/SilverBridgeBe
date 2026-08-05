package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.medication.entity.MedicationReminderLog;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 선점된 복약 알림을 실제로 발송한다.
 *
 * <p><b>트랜잭션 밖에서 보낸다</b> — 선점({@link MedicationReminderPlanner})이 커밋된 뒤 호출되므로,
 * FCM·SMS 네트워크 지연이 DB 커넥션을 붙잡지 않는다.</p>
 *
 * <p><b>채널은 FCM과 문자뿐</b>이다. {@link NotificationType#MEDICATION_REMINDER}가
 * {@code SETTINGS_ONLY}라 사용자가 켠 채널로만 나가고, 알림톡은 승인 템플릿 매핑이 없어 스킵된다.
 * WebSocket은 보내지 않는다(복용 체크 반영용 {@code medication-taken}과 성격이 다르다).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicationReminderService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final MedicationReminderPlanner planner;
    private final NotificationDispatcher notificationDispatcher;

    /** 복용 시각이 도래한 약의 최초 알림을 보낸다. */
    public int sendFirstReminders() {
        return send(planner.claimFirstReminders());
    }

    /** 최초 알림 후에도 체크되지 않은 약의 재알림을 보낸다. */
    public int sendRetryReminders() {
        return send(planner.claimRetryReminders());
    }

    private int send(List<MedicationReminderTarget> targets) {
        int sent = 0;
        for (MedicationReminderTarget target : targets) {
            try {
                notificationDispatcher.dispatch(target.wardId(), NotificationType.MEDICATION_REMINDER, content(target));
                sent++;
            } catch (RuntimeException e) {
                // 이미 발송 기록을 선점했으므로 이 회차는 재시도하지 않는다(중복 발송 방지 우선).
                // 최초 알림이 실패해도 재알림이 두 번째 기회가 된다.
                log.error("[MEDICATION-REMINDER] 발송 실패 medicationId={}, wardId={}, attempt={}",
                        target.medicationId(), target.wardId(), target.attempt(), e);
            }
        }
        return sent;
    }

    /**
     * 알림 문구. 어르신이 읽을 문장이라 무엇을·언제·얼마나인지 한 줄에 담는다.
     * 재알림은 "안 드신 것 같다"가 아니라 "체크가 안 되어 있다"로 적는다 — 실제로는 드시고 체크만 안 한 경우가 많다.
     */
    private NotificationContent content(MedicationReminderTarget target) {
        boolean retry = target.attempt() == MedicationReminderLog.ATTEMPT_RETRY;
        String title = retry ? "약 드셨나요?" : "복약 시간이에요";
        String body = retry
                ? "%s이(가) 아직 복용 체크되지 않았어요.".formatted(target.name())
                : "%s 드실 시간입니다. %s %s · %d정".formatted(
                        target.name(),
                        target.timeSlot().label(),
                        target.doseTime().format(TIME_FORMAT),
                        target.doseAmount());

        return NotificationContent.of(title, body, Map.of(
                "type", "MEDICATION_REMINDER",
                "medicationId", String.valueOf(target.medicationId()),
                "doseDate", target.doseDate().toString(),
                "attempt", String.valueOf(target.attempt())));
    }
}

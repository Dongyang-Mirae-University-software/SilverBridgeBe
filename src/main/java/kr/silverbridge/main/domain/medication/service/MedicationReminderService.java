package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.medication.entity.MedicationReminderLog;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.channel.WardNotificationText;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 선점된 복약 알림을 실제로 발송한다.
 *
 * <p><b>트랜잭션 밖에서 보낸다</b> — 선점({@link MedicationReminderPlanner})이 커밋된 뒤 호출되므로,
 * FCM·SMS 네트워크 지연이 DB 커넥션을 붙잡지 않는다.</p>
 *
 * <p><b>채널은 FCM과 문자뿐</b>이다. {@link NotificationType#MEDICATION_REMINDER}가
 * {@code SETTINGS_ONLY}라 사용자가 켠 채널로만 나가고, 알림톡은 승인 템플릿 매핑이 없어 스킵된다.
 * WebSocket은 보내지 않는다(복용 체크 반영용 {@code medication-taken}과 성격이 다르다).</p>
 *
 * <p><b>같은 시각의 약은 알림 한 건으로 묶는다</b>(2026-10-23) - 약 6개를 아침에 드시면 푸시·문자가 6건 나가던 것을 1건으로.
 * 선점(발송 기록·UNIQUE)은 지금처럼 <b>약 단위</b>이고, 묶음은 선점이 끝난 대상을 보내기 직전에 메모리에서만 만든다 -
 * 선점 후 발송 순서와 중복 방지는 그대로다. 같은 시각 약이 서로 다른 주기에 선점되면(늦게 등록한 약 등) 알림이 나뉠 수 있다(수용).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicationReminderService {

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

    /** @return 실제로 나간 알림 수(같은 시각의 약 묶음 하나 = 1건) */
    private int send(List<MedicationReminderTarget> targets) {
        Map<GroupKey, List<MedicationReminderTarget>> groups = targets.stream().collect(Collectors.groupingBy(
                target -> new GroupKey(target.wardId(), target.doseDate(), target.doseTime(), target.attempt()),
                LinkedHashMap::new, Collectors.toList()));
        int sent = 0;
        for (List<MedicationReminderTarget> group : groups.values()) {
            MedicationReminderTarget first = group.get(0);
            try {
                notificationDispatcher.dispatch(first.wardId(), first.wardId(), NotificationType.MEDICATION_REMINDER,
                        group.size() == 1 ? content(first) : groupContent(group));
                sent++;
            } catch (RuntimeException e) {
                // 이미 발송 기록을 선점했으므로 이 회차는 재시도하지 않는다(중복 발송 방지 우선).
                // 최초 알림이 실패해도 재알림이 두 번째 기회가 된다.
                log.error("[MEDICATION-REMINDER] 발송 실패 medicationIds={}, wardId={}, attempt={}",
                        medicationIds(group), first.wardId(), first.attempt(), e);
            }
        }
        return sent;
    }

    /**
     * 묶음 키에 시간대는 넣지 않는다(2026-10-30) - 문구에 시간대 이름이 없으니(2026-10-27) 같은 시각이면 한 알림으로 충분하다.
     * 시간대를 넣으면 시간대와 시각이 어긋나 저장된 옛 약("점심 01:40")이 같은 시각의 다른 약과 갈라져 같은 분에 두 건이 나갔다.
     */
    private record GroupKey(String wardId, LocalDate doseDate, LocalTime doseTime, int attempt) {
    }

    /**
     * 알림 문구(2026-10-27 단순화). 피보호자 휴대폰 문자로도 나가므로 {@link WardNotificationText} 규칙대로 단문 한 줄이다 -
     * 약 이름·개수·용량·시각은 넣지 않는다(앱 화면에서 본다). 재알림은 "안 드셨다"가 아니라 체크를 부탁하는 말로 적는다 —
     * 실제로는 드시고 체크만 안 한 경우가 많다.
     */
    static final String FIRST_BODY = "약 드실 시간이에요.";
    static final String RETRY_BODY = "약 드셨으면 앱에서 '먹었어요'를 눌러 주세요.";

    private NotificationContent content(MedicationReminderTarget target) {
        return NotificationContent.of(WardNotificationText.TITLE, body(target), Map.of(
                "type", "MEDICATION_REMINDER",
                "medicationId", String.valueOf(target.medicationId()),
                "doseDate", target.doseDate().toString(),
                "attempt", String.valueOf(target.attempt())));
    }

    /**
     * 같은 시각 약 여러 개의 묶음 알림. 문구는 한 건과 같고, data의 {@code medicationId}는 첫 약(옛 앱 호환),
     * {@code medicationIds}는 묶음 전체(쉼표 구분)다.
     */
    private NotificationContent groupContent(List<MedicationReminderTarget> group) {
        MedicationReminderTarget first = group.get(0);
        return NotificationContent.of(WardNotificationText.TITLE, body(first), Map.of(
                "type", "MEDICATION_REMINDER",
                "medicationId", String.valueOf(first.medicationId()),
                "medicationIds", medicationIds(group),
                "doseDate", first.doseDate().toString(),
                "attempt", String.valueOf(first.attempt())));
    }

    private static String body(MedicationReminderTarget target) {
        return target.attempt() == MedicationReminderLog.ATTEMPT_RETRY ? RETRY_BODY : FIRST_BODY;
    }

    private static String medicationIds(List<MedicationReminderTarget> group) {
        return group.stream().map(target -> String.valueOf(target.medicationId())).collect(Collectors.joining(","));
    }
}

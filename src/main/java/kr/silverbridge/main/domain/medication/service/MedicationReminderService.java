package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.medication.entity.DoseAmounts;
import kr.silverbridge.main.domain.medication.entity.MedicationReminderLog;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    /** 묶음 알림 본문에 이름을 적는 약 수 - 나머지는 "외 N개"(문자가 너무 길어지지 않게). */
    static final int NAMES_SHOWN = 2;

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
                target -> new GroupKey(target.wardId(), target.doseDate(), target.timeSlot(), target.doseTime(), target.attempt()),
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

    private record GroupKey(String wardId, LocalDate doseDate, MedicationTimeSlot timeSlot, LocalTime doseTime, int attempt) {
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
                : "%s 드실 시간입니다. %s %s · %s".formatted(
                        target.name(),
                        target.timeSlot().label(),
                        target.doseTime().format(TIME_FORMAT),
                        DoseAmounts.label(target.doseAmount()));

        return NotificationContent.of(title, body, Map.of(
                "type", "MEDICATION_REMINDER",
                "medicationId", String.valueOf(target.medicationId()),
                "doseDate", target.doseDate().toString(),
                "attempt", String.valueOf(target.attempt())));
    }

    /**
     * 같은 시각 약 여러 개의 묶음 문구. 첫 알림은 이름과 용량을, 재알림은 아직 체크되지 않은 약 이름을 적는다.
     * "아침 약 6개 드실 시간입니다(08:00). 혈압약 1정, 감기약 반 알 외 4개"
     * "아침 약 중 2개가 아직 복용 체크되지 않았어요. 혈압약, 감기약"
     *
     * <p>data의 {@code medicationId}는 첫 약(옛 앱 호환), {@code medicationIds}는 묶음 전체(쉼표 구분)다.</p>
     */
    private NotificationContent groupContent(List<MedicationReminderTarget> group) {
        MedicationReminderTarget first = group.get(0);
        boolean retry = first.attempt() == MedicationReminderLog.ATTEMPT_RETRY;
        String slot = first.timeSlot().label();
        String title = retry ? "약 드셨나요?" : "복약 시간이에요";
        String body = retry
                ? "%s 약 중 %d개가 아직 복용 체크되지 않았어요. %s".formatted(
                        slot, group.size(), names(group, false))
                : "%s 약 %d개 드실 시간입니다(%s). %s".formatted(
                        slot, group.size(), first.doseTime().format(TIME_FORMAT), names(group, true));

        return NotificationContent.of(title, body, Map.of(
                "type", "MEDICATION_REMINDER",
                "medicationId", String.valueOf(first.medicationId()),
                "medicationIds", medicationIds(group),
                "doseDate", first.doseDate().toString(),
                "attempt", String.valueOf(first.attempt())));
    }

    /** "혈압약 1정, 감기약 반 알 외 4개" - 앞의 {@link #NAMES_SHOWN}개만 적는다. */
    private static String names(List<MedicationReminderTarget> group, boolean withDose) {
        String shown = group.stream().limit(NAMES_SHOWN)
                .map(target -> withDose ? target.name() + " " + DoseAmounts.label(target.doseAmount()) : target.name())
                .collect(Collectors.joining(", "));
        int rest = group.size() - NAMES_SHOWN;
        return rest > 0 ? shown + " 외 " + rest + "개" : shown;
    }

    private static String medicationIds(List<MedicationReminderTarget> group) {
        return group.stream().map(target -> String.valueOf(target.medicationId())).collect(Collectors.joining(","));
    }
}

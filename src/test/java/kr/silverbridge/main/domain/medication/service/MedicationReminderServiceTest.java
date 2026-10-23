package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.medication.entity.MedicationReminderLog;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * MedicationReminderService 단위 테스트 — 선점된 대상에게 실제로 어떤 문구가 어느 타입으로 나가는지.
 *
 * <p>발송 실패가 스케줄러 전체를 멈추지 않는 것(격리)도 함께 본다.</p>
 */
@ExtendWith(MockitoExtension.class)
class MedicationReminderServiceTest {

    @Mock private MedicationReminderPlanner planner;
    @Mock private NotificationDispatcher notificationDispatcher;

    @InjectMocks private MedicationReminderService reminderService;

    private static final String WARD_ID = "WD0001";

    @Test
    @DisplayName("최초 알림 — MEDICATION_REMINDER로 약 이름·시간대·용량이 담긴 문구를 보낸다")
    void 최초알림_문구() {
        when(planner.claimFirstReminders()).thenReturn(List.of(target(MedicationReminderLog.ATTEMPT_FIRST)));

        assertThat(reminderService.sendFirstReminders()).isEqualTo(1);

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(
                eq(WARD_ID), any(), eq(NotificationType.MEDICATION_REMINDER), captor.capture());
        NotificationContent content = captor.getValue();
        assertThat(content.title()).isEqualTo("복약 시간이에요");
        assertThat(content.body()).contains("혈압약", "아침", "08:00", "1정");
        assertThat(content.data())
                .containsEntry("type", "MEDICATION_REMINDER")
                .containsEntry("attempt", "1");
    }

    @Test
    @DisplayName("반 알 — 문구에 '0.5정'이 아니라 '반 알'로, 1.5는 '1정 반'으로 나간다(같은 시각이라 한 알림으로 묶인다)")
    void 반알_문구() {
        MedicationReminderTarget half = new MedicationReminderTarget(1L, WARD_ID, "감기약", MedicationTimeSlot.DINNER,
                LocalTime.of(18, 0), new BigDecimal("0.5"), MedicationClock.today(), MedicationReminderLog.ATTEMPT_FIRST);
        MedicationReminderTarget oneAndHalf = new MedicationReminderTarget(2L, WARD_ID, "진통제", MedicationTimeSlot.DINNER,
                LocalTime.of(18, 0), new BigDecimal("1.5"), MedicationClock.today(), MedicationReminderLog.ATTEMPT_FIRST);
        when(planner.claimFirstReminders()).thenReturn(List.of(half, oneAndHalf));

        reminderService.sendFirstReminders();

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(any(), any(), eq(NotificationType.MEDICATION_REMINDER), captor.capture());
        assertThat(captor.getValue().body()).endsWith("감기약 반 알, 진통제 1정 반").doesNotContain("0.5");
    }

    @Test
    @DisplayName("같은 시각 약 6개 — 알림 1건으로 묶고, 이름 2개 + '외 4개', data에 전체 약 ID를 싣는다 (2026-10-23)")
    void 같은시각_묶음() {
        List<MedicationReminderTarget> six = new java.util.ArrayList<>();
        String[] names = {"코싹열정", "신일이부프로펜정", "알마겔정", "소말겐정", "엘도스캡슐", "코푸시럽"};
        for (int i = 0; i < names.length; i++) {
            six.add(new MedicationReminderTarget((long) (i + 1), WARD_ID, names[i], MedicationTimeSlot.MORNING,
                    LocalTime.of(8, 0), BigDecimal.ONE, MedicationClock.today(), MedicationReminderLog.ATTEMPT_FIRST));
        }
        when(planner.claimFirstReminders()).thenReturn(six);

        assertThat(reminderService.sendFirstReminders()).isEqualTo(1);

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher, times(1)).dispatch(
                eq(WARD_ID), eq(WARD_ID), eq(NotificationType.MEDICATION_REMINDER), captor.capture());
        NotificationContent content = captor.getValue();
        assertThat(content.title()).isEqualTo("복약 시간이에요");
        assertThat(content.body()).isEqualTo("아침 약 6개 드실 시간입니다(08:00). 코싹열정 1정, 신일이부프로펜정 1정 외 4개");
        assertThat(content.data())
                .containsEntry("type", "MEDICATION_REMINDER")
                .containsEntry("medicationId", "1")
                .containsEntry("medicationIds", "1,2,3,4,5,6")
                .containsEntry("attempt", "1");
    }

    @Test
    @DisplayName("묶음 재알림 — 아직 체크되지 않은 약만 이름으로, 단정하지 않는 문구")
    void 묶음_재알림() {
        when(planner.claimRetryReminders()).thenReturn(List.of(
                target(MedicationReminderLog.ATTEMPT_RETRY, 1L, "혈압약"),
                target(MedicationReminderLog.ATTEMPT_RETRY, 2L, "감기약")));

        reminderService.sendRetryReminders();

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(any(), any(), eq(NotificationType.MEDICATION_REMINDER), captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("약 드셨나요?");
        assertThat(captor.getValue().body()).isEqualTo("아침 약 중 2개가 아직 복용 체크되지 않았어요. 혈압약, 감기약");
        assertThat(captor.getValue().body()).doesNotContain("안 드셨");
    }

    @Test
    @DisplayName("시각·피보호자·회차가 다르면 따로 보낸다 — 묶음은 같은 시각의 같은 사람 약만")
    void 다른시각_다른사람_따로() {
        MedicationReminderTarget morning = target(MedicationReminderLog.ATTEMPT_FIRST, 1L, "혈압약");
        MedicationReminderTarget lunch = new MedicationReminderTarget(2L, WARD_ID, "위장약", MedicationTimeSlot.LUNCH,
                LocalTime.of(12, 0), BigDecimal.ONE, MedicationClock.today(), MedicationReminderLog.ATTEMPT_FIRST);
        MedicationReminderTarget otherWard = new MedicationReminderTarget(3L, "WD0002", "혈압약", MedicationTimeSlot.MORNING,
                LocalTime.of(8, 0), BigDecimal.ONE, MedicationClock.today(), MedicationReminderLog.ATTEMPT_FIRST);
        when(planner.claimFirstReminders()).thenReturn(List.of(morning, lunch, otherWard));

        assertThat(reminderService.sendFirstReminders()).isEqualTo(3);

        verify(notificationDispatcher).dispatch(eq("WD0002"), eq("WD0002"), any(), any());
        verify(notificationDispatcher, times(2)).dispatch(eq(WARD_ID), eq(WARD_ID), any(), any());
    }

    @Test
    @DisplayName("재알림 — '아직 체크되지 않았다'로 안내한다(안 드셨다고 단정하지 않는다)")
    void 재알림_문구() {
        when(planner.claimRetryReminders()).thenReturn(List.of(target(MedicationReminderLog.ATTEMPT_RETRY)));

        assertThat(reminderService.sendRetryReminders()).isEqualTo(1);

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(any(), any(), eq(NotificationType.MEDICATION_REMINDER), captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("약 드셨나요?");
        assertThat(captor.getValue().body()).contains("혈압약", "체크");
        assertThat(captor.getValue().data()).containsEntry("attempt", "2");
    }

    @Test
    @DisplayName("보낼 대상이 없으면 디스패처를 호출하지 않는다")
    void 대상없음() {
        when(planner.claimFirstReminders()).thenReturn(List.of());

        assertThat(reminderService.sendFirstReminders()).isZero();
        verify(notificationDispatcher, never()).dispatch(any(), any(), any(), any());
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지는 계속 발송하고 예외를 밖으로 내보내지 않는다")
    void 발송실패_격리() {
        MedicationReminderTarget other = new MedicationReminderTarget(2L, "WD0002", "혈압약", MedicationTimeSlot.MORNING,
                LocalTime.of(8, 0), BigDecimal.ONE, MedicationClock.today(), MedicationReminderLog.ATTEMPT_FIRST);
        when(planner.claimFirstReminders()).thenReturn(List.of(target(MedicationReminderLog.ATTEMPT_FIRST, 1L), other));
        doThrow(new IllegalStateException("FCM 오류"))
                .doReturn(null)
                .when(notificationDispatcher).dispatch(any(), any(), any(), any());

        int sent = reminderService.sendFirstReminders();

        assertThat(sent).isEqualTo(1);
        verify(notificationDispatcher, times(2)).dispatch(any(), any(), any(), any());
        assertThatNoException().isThrownBy(() -> reminderService.sendRetryReminders());
    }

    private static MedicationReminderTarget target(int attempt) {
        return target(attempt, 1L);
    }

    private static MedicationReminderTarget target(int attempt, Long medicationId) {
        return target(attempt, medicationId, "혈압약");
    }

    private static MedicationReminderTarget target(int attempt, Long medicationId, String name) {
        return new MedicationReminderTarget(
                medicationId, WARD_ID, name, MedicationTimeSlot.MORNING,
                LocalTime.of(8, 0), BigDecimal.ONE, MedicationClock.today(), attempt);
    }
}

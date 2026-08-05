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
                eq(WARD_ID), eq(NotificationType.MEDICATION_REMINDER), captor.capture());
        NotificationContent content = captor.getValue();
        assertThat(content.title()).isEqualTo("복약 시간이에요");
        assertThat(content.body()).contains("혈압약", "아침", "08:00", "1정");
        assertThat(content.data())
                .containsEntry("type", "MEDICATION_REMINDER")
                .containsEntry("attempt", "1");
    }

    @Test
    @DisplayName("재알림 — '아직 체크되지 않았다'로 안내한다(안 드셨다고 단정하지 않는다)")
    void 재알림_문구() {
        when(planner.claimRetryReminders()).thenReturn(List.of(target(MedicationReminderLog.ATTEMPT_RETRY)));

        assertThat(reminderService.sendRetryReminders()).isEqualTo(1);

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(any(), eq(NotificationType.MEDICATION_REMINDER), captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("약 드셨나요?");
        assertThat(captor.getValue().body()).contains("혈압약", "체크");
        assertThat(captor.getValue().data()).containsEntry("attempt", "2");
    }

    @Test
    @DisplayName("보낼 대상이 없으면 디스패처를 호출하지 않는다")
    void 대상없음() {
        when(planner.claimFirstReminders()).thenReturn(List.of());

        assertThat(reminderService.sendFirstReminders()).isZero();
        verify(notificationDispatcher, never()).dispatch(any(), any(), any());
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지는 계속 발송하고 예외를 밖으로 내보내지 않는다")
    void 발송실패_격리() {
        when(planner.claimFirstReminders()).thenReturn(List.of(
                target(MedicationReminderLog.ATTEMPT_FIRST, 1L),
                target(MedicationReminderLog.ATTEMPT_FIRST, 2L)));
        doThrow(new IllegalStateException("FCM 오류"))
                .doNothing()
                .when(notificationDispatcher).dispatch(any(), any(), any());

        int sent = reminderService.sendFirstReminders();

        assertThat(sent).isEqualTo(1);
        verify(notificationDispatcher, times(2)).dispatch(any(), any(), any());
        assertThatNoException().isThrownBy(() -> reminderService.sendRetryReminders());
    }

    private static MedicationReminderTarget target(int attempt) {
        return target(attempt, 1L);
    }

    private static MedicationReminderTarget target(int attempt, Long medicationId) {
        return new MedicationReminderTarget(
                medicationId, WARD_ID, "혈압약", MedicationTimeSlot.MORNING,
                LocalTime.of(8, 0), 1, MedicationClock.today(), attempt);
    }
}

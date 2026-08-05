package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.medication.config.MedicationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.*;

/**
 * MedicationReminderScheduler 단위 테스트 — 킬 스위치와 예외 격리.
 *
 * <p>한 주기의 실패가 예외로 새어 나가면 스케줄러 자체가 멈춰 이후 알림이 전부 사라지므로,
 * "실패해도 다음 단계·다음 주기가 돈다"를 보장하는 것이 핵심이다.</p>
 *
 * <p>피보호자 알림(2차)과 보호자 미복용 요약(3차)의 <b>킬 스위치가 독립</b>인 것도 함께 본다 —
 * 보호자 쪽을 꺼도 피보호자의 복용 알림은 계속 나가야 한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class MedicationReminderSchedulerTest {

    @Mock private MedicationReminderService reminderService;
    @Mock private MedicationMissedAlertService missedAlertService;

    private MedicationProperties properties;
    private MedicationReminderScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new MedicationProperties();
        scheduler = new MedicationReminderScheduler(reminderService, missedAlertService, properties);
    }

    @Test
    @DisplayName("기본 상태에서는 최초 알림·재알림·미복용 요약을 모두 시도한다")
    void 정상주기() {
        scheduler.sendDueReminders();

        verify(reminderService).sendFirstReminders();
        verify(reminderService).sendRetryReminders();
        verify(missedAlertService).sendMissedAlerts();
    }

    @Test
    @DisplayName("피보호자 알림 킬 스위치를 꺼도 보호자 미복용 요약은 계속 나간다(독립)")
    void 킬스위치_피보호자만_차단() {
        properties.setEnabled(false);

        scheduler.sendDueReminders();

        verifyNoInteractions(reminderService);
        verify(missedAlertService).sendMissedAlerts();
    }

    @Test
    @DisplayName("보호자 요약 킬 스위치를 꺼도 피보호자 복용 알림은 계속 나간다(독립)")
    void 킬스위치_보호자만_차단() {
        properties.getMissedAlert().setEnabled(false);

        scheduler.sendDueReminders();

        verify(reminderService).sendFirstReminders();
        verify(reminderService).sendRetryReminders();
        verifyNoInteractions(missedAlertService);
    }

    @Test
    @DisplayName("최초 알림이 실패해도 재알림·미복용 요약은 계속 시도하고 예외를 밖으로 내보내지 않는다")
    void 실패해도_다음단계_진행() {
        when(reminderService.sendFirstReminders()).thenThrow(new IllegalStateException("DB 순단"));

        assertThatNoException().isThrownBy(() -> scheduler.sendDueReminders());

        verify(reminderService).sendRetryReminders();
        verify(missedAlertService).sendMissedAlerts();
    }

    @Test
    @DisplayName("미복용 요약이 실패해도 예외를 밖으로 내보내지 않는다")
    void 요약실패_격리() {
        when(missedAlertService.sendMissedAlerts()).thenThrow(new IllegalStateException("FCM 오류"));

        assertThatNoException().isThrownBy(() -> scheduler.sendDueReminders());
    }
}

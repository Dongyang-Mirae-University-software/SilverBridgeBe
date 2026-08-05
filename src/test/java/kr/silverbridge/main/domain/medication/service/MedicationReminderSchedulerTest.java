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
 * "실패해도 다음 주기가 돈다"를 보장하는 것이 핵심이다.</p>
 */
@ExtendWith(MockitoExtension.class)
class MedicationReminderSchedulerTest {

    @Mock private MedicationReminderService reminderService;

    private MedicationProperties properties;
    private MedicationReminderScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new MedicationProperties();
        scheduler = new MedicationReminderScheduler(reminderService, properties);
    }

    @Test
    @DisplayName("기본 상태에서는 최초 알림과 재알림을 모두 시도한다")
    void 정상주기() {
        scheduler.sendDueReminders();

        verify(reminderService).sendFirstReminders();
        verify(reminderService).sendRetryReminders();
    }

    @Test
    @DisplayName("킬 스위치(enabled=false)면 아무것도 보내지 않는다")
    void 킬스위치() {
        properties.setEnabled(false);

        scheduler.sendDueReminders();

        verifyNoInteractions(reminderService);
    }

    @Test
    @DisplayName("최초 알림이 실패해도 재알림은 계속 시도하고 예외를 밖으로 내보내지 않는다")
    void 실패해도_다음단계_진행() {
        when(reminderService.sendFirstReminders()).thenThrow(new IllegalStateException("DB 순단"));

        assertThatNoException().isThrownBy(() -> scheduler.sendDueReminders());

        verify(reminderService).sendRetryReminders();
    }
}

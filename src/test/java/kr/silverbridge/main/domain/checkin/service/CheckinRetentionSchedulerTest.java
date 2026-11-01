package kr.silverbridge.main.domain.checkin.service;

import kr.silverbridge.main.domain.checkin.repository.CheckinNotifyLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 점검 M-4: 발송 기록 90일·대화와 결과 365일, 0 이하면 그 삭제만 끈다. */
class CheckinRetentionSchedulerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);
    private final CheckinNotifyLogRepository repository = mock(CheckinNotifyLogRepository.class);
    private final CheckinClock clock = mock(CheckinClock.class);

    @BeforeEach
    void setUp() {
        when(clock.now()).thenReturn(OffsetDateTime.of(TODAY.atTime(5, 10), ZoneOffset.ofHours(9)));
    }

    @Test
    @DisplayName("기준 날짜 전 행을 지운다")
    void deletesBefore() {
        new CheckinRetentionScheduler(repository, clock, 90, 365).cleanup();

        verify(repository).deletePromptLogsBefore(TODAY.minusDays(90));
        verify(repository).deleteGuardianAlertLogsBefore(TODAY.minusDays(90));
        verify(repository).deleteSessionsBefore(TODAY.minusDays(365));
    }

    @Test
    @DisplayName("0 이하면 그 삭제만 끈다")
    void disabled() {
        new CheckinRetentionScheduler(repository, clock, 0, 365).cleanup();
        verify(repository, never()).deletePromptLogsBefore(any());
        verify(repository).deleteSessionsBefore(TODAY.minusDays(365));
    }

    @Test
    @DisplayName("실패해도 예외를 내보내지 않는다 / 05:10 KST - 다른 일일 정리와 겹치지 않는다")
    void failureAndSchedule() throws Exception {
        when(repository.deletePromptLogsBefore(any())).thenThrow(new RuntimeException("db"));
        assertThatNoException().isThrownBy(() -> new CheckinRetentionScheduler(repository, clock, 90, 365).cleanup());

        Scheduled scheduled = CheckinRetentionScheduler.class.getMethod("cleanup").getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("0 10 5 * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    }
}

package kr.silverbridge.main.global.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 관리자 감사 로그 보관 정리 (2026-10-31 전체 점검 D-2). */
@ExtendWith(MockitoExtension.class)
class AdminAuditLogRetentionSchedulerTest {

    @Mock private AdminAuditLogRepository repository;

    @Test
    @DisplayName("보관 기간(365일)보다 오래된 감사 로그를 지운다")
    void 정리() {
        new AdminAuditLogRetentionScheduler(repository, 365).cleanup();

        ArgumentCaptor<OffsetDateTime> threshold = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).deleteOlderThan(threshold.capture());
        assertThat(Duration.between(threshold.getValue(), OffsetDateTime.now().minusDays(365)).abs())
                .isLessThan(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("보관 기간이 0 이하면 지우지 않는다")
    void 끔() {
        new AdminAuditLogRetentionScheduler(repository, 0).cleanup();

        verify(repository, never()).deleteOlderThan(any());
    }

    @Test
    @DisplayName("DB 오류는 삼킨다")
    void 예외삼킴() {
        when(repository.deleteOlderThan(any())).thenThrow(new IllegalStateException("db"));

        assertThatCode(() -> new AdminAuditLogRetentionScheduler(repository, 365).cleanup())
                .doesNotThrowAnyException();
    }
}

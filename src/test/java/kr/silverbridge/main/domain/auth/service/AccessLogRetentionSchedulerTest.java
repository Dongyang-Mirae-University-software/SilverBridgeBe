package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.repository.AccessLogRepository;
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

/** 접속 로그 보관 정리 (2026-10-31 전체 점검 D-2). */
@ExtendWith(MockitoExtension.class)
class AccessLogRetentionSchedulerTest {

    @Mock private AccessLogRepository accessLogRepository;

    @Test
    @DisplayName("탈퇴자 IP·UA를 비우고, 보관 기간(90일)보다 오래된 로그를 지운다")
    void 정리() {
        new AccessLogRetentionScheduler(accessLogRepository, 90).cleanup();

        verify(accessLogRepository).anonymizeWithdrawn();
        ArgumentCaptor<OffsetDateTime> threshold = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(accessLogRepository).deleteOlderThan(threshold.capture());
        assertThat(Duration.between(threshold.getValue(), OffsetDateTime.now().minusDays(90)).abs())
                .isLessThan(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("보관 기간이 0 이하면 삭제만 끄고 익명화는 한다")
    void 삭제끔() {
        new AccessLogRetentionScheduler(accessLogRepository, 0).cleanup();

        verify(accessLogRepository).anonymizeWithdrawn();
        verify(accessLogRepository, never()).deleteOlderThan(any());
    }

    @Test
    @DisplayName("DB 오류는 삼킨다 - 한 주기의 실패가 스케줄러를 멈추지 않는다")
    void 예외삼킴() {
        when(accessLogRepository.anonymizeWithdrawn()).thenThrow(new IllegalStateException("db"));

        assertThatCode(() -> new AccessLogRetentionScheduler(accessLogRepository, 90).cleanup())
                .doesNotThrowAnyException();
    }
}

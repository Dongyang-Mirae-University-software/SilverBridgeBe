package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.entity.CompanionEndReason;
import kr.silverbridge.main.domain.companion.entity.CompanionMood;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.event.CompanionRiskDetectedEvent;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 위험 알림 대화당 1번(선점에 이긴 쪽만 이벤트)과 끝난 대화 보호(2026-11-04 점검 M-2·M-3).
 * 선점 쿼리 자체는 {@code CompanionSessionRepositoryIntegrationTest}가 실 DB로 본다.
 */
class CompanionSessionWriterTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 4, 9, 10, 0, 0, ZoneOffset.ofHours(9));

    private CompanionSessionRepository repository;
    private ApplicationEventPublisher publisher;
    private CompanionSessionWriter writer;

    @BeforeEach
    void setUp() {
        repository = mock(CompanionSessionRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        writer = new CompanionSessionWriter(repository, publisher);
    }

    @Test
    @DisplayName("위험 신호 턴: 선점에 이기면 이벤트 1번, 지면(이미 보냄) 내지 않는다")
    void riskTurnPublishesOnlyWhenClaimWon() {
        when(repository.findById(1L)).thenReturn(Optional.of(open(1L)));
        when(repository.claimRiskNotification(eq(1L), any())).thenReturn(1, 0);

        writer.recordTurn(1L, 2, true, NOW);
        writer.recordTurn(1L, 3, true, NOW);

        verify(publisher).publishEvent(new CompanionRiskDetectedEvent("CW0001", 1L));
        verify(repository, org.mockito.Mockito.times(2)).claimRiskNotification(eq(1L), any());
    }

    @Test
    @DisplayName("위험 신호가 없는 턴은 선점하지 않는다")
    void plainTurnDoesNotClaim() {
        when(repository.findById(2L)).thenReturn(Optional.of(open(2L)));

        writer.recordTurn(2L, 1, false, NOW);

        verify(repository, never()).claimRiskNotification(anyLong(), any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("마감 결과가 URGENT면 선점해 아직 안 보냈을 때만 이벤트를 낸다")
    void urgentFinishClaims() {
        when(repository.findById(3L)).thenReturn(Optional.of(open(3L)));
        when(repository.claimRiskNotification(eq(3L), any())).thenReturn(1);

        CompanionSession finished = writer.finish(3L, result(CompanionEndReason.USER_STOP, true), NOW);

        assertThat(finished.getAlertLevel()).isEqualTo(CompanionSession.ALERT_URGENT);
        verify(publisher).publishEvent(new CompanionRiskDetectedEvent("CW0001", 3L));
    }

    @Test
    @DisplayName("이미 끝난 대화는 다시 마감해도 종료 이유·끝난 시각을 덮지 않는다(점검 M-3)")
    void finishedSessionIsNotOverwritten() {
        CompanionSession done = open(4L);
        done.finish(CompanionEndReason.USER_STOP, CompanionMood.CALM, 3, 3, 0, false, false, null, NOW);
        when(repository.findById(4L)).thenReturn(Optional.of(done));

        writer.finish(4L, result(CompanionEndReason.NO_RESPONSE, true), NOW.plusMinutes(20));
        writer.recordTurn(4L, 9, true, NOW.plusMinutes(20));

        assertThat(done.getEndReason()).isEqualTo(CompanionEndReason.USER_STOP);
        assertThat(done.getEndedAt()).isEqualTo(NOW);
        assertThat(done.getTurnCount()).isEqualTo(3);
        verify(repository, never()).claimRiskNotification(anyLong(), any());
    }

    private static CompanionSession open(long id) {
        CompanionSession session = CompanionSession.claim("CW0001", LocalDate.of(2026, 11, 4), CompanionSlot.S09, 1L, NOW);
        ReflectionTestUtils.setField(session, "id", id);
        session.attachDialogue("d-" + id, 8, NOW);
        return session;
    }

    private static CompanionResult result(CompanionEndReason endReason, boolean urgent) {
        return new CompanionResult("d", endReason, null, 2, 2, 0, urgent, urgent, null);
    }
}

package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.entity.CompanionEndReason;
import kr.silverbridge.main.domain.companion.entity.CompanionMood;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.event.CompanionRiskDetectedEvent;
import kr.silverbridge.main.domain.companion.event.CompanionRiskPatternCheckEvent;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
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
    private CompanionProperties properties;

    @BeforeEach
    void setUp() {
        repository = mock(CompanionSessionRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        properties = new CompanionProperties();
        writer = new CompanionSessionWriter(repository, publisher, properties);
    }

    @Test
    @DisplayName("위험 신호 턴: 선점에 이기면 이벤트 1번, 지면(이미 보냄) 내지 않는다")
    void riskTurnPublishesOnlyWhenClaimWon() {
        when(repository.findById(1L)).thenReturn(Optional.of(open(1L)));
        when(repository.claimRiskNotification(eq(1L), any())).thenReturn(1, 0);

        writer.recordTurn(1L, 2, true, null, NOW);
        writer.recordTurn(1L, 3, true, null, NOW);

        verify(publisher).publishEvent(new CompanionRiskDetectedEvent("CW0001", 1L));
        verify(repository, org.mockito.Mockito.times(2)).claimRiskNotification(eq(1L), any());
    }

    @Test
    @DisplayName("위험 신호가 없는 턴은 선점하지 않는다")
    void plainTurnDoesNotClaim() {
        when(repository.findById(2L)).thenReturn(Optional.of(open(2L)));

        writer.recordTurn(2L, 1, false, null, NOW);

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
        writer.recordTurn(4L, 9, true, null, NOW.plusMinutes(20));

        assertThat(done.getEndReason()).isEqualTo(CompanionEndReason.USER_STOP);
        assertThat(done.getEndedAt()).isEqualTo(NOW);
        assertThat(done.getTurnCount()).isEqualTo(3);
        verify(repository, never()).claimRiskNotification(anyLong(), any());
    }

    @Test
    @DisplayName("턴 위험 확률은 대화 최댓값으로만 남는다(낮은 값·null이 높은 값을 덮지 않음)")
    void maxRiskProbabilityKeepsHighest() {
        CompanionSession session = open(5L);
        when(repository.findById(5L)).thenReturn(Optional.of(session));

        writer.recordTurn(5L, 1, false, new BigDecimal("0.4200"), NOW);
        writer.recordTurn(5L, 2, false, new BigDecimal("0.9100"), NOW);
        writer.recordTurn(5L, 3, false, new BigDecimal("0.1000"), NOW);
        writer.recordTurn(5L, 4, false, null, NOW);

        assertThat(session.getMaxRiskProb()).isEqualByComparingTo("0.91");
    }

    @Test
    @DisplayName("누적 스위치가 켜져 있고 확률이 기준 이상이면 누적 판정 이벤트를 낸다(기준값과 같아도)")
    void highProbabilityPublishesPatternCheck() {
        properties.getRiskPattern().setEnabled(true);
        when(repository.findById(6L)).thenReturn(Optional.of(open(6L)));

        writer.recordTurn(6L, 1, false, new BigDecimal("0.9700"), NOW);

        verify(publisher).publishEvent(new CompanionRiskPatternCheckEvent("CW0001", NOW.toLocalDate()));
    }

    @Test
    @DisplayName("기준 미만·null 확률이거나 스위치가 꺼져 있으면 누적 판정 이벤트를 내지 않는다")
    void noPatternCheckBelowThresholdOrDisabled() {
        when(repository.findById(7L)).thenReturn(Optional.of(open(7L)));

        writer.recordTurn(7L, 1, false, new BigDecimal("0.9900"), NOW); // 스위치 꺼짐(기본)
        properties.getRiskPattern().setEnabled(true);
        writer.recordTurn(7L, 2, false, new BigDecimal("0.9699"), NOW);
        writer.recordTurn(7L, 3, false, null, NOW);

        verify(publisher, never()).publishEvent(any(CompanionRiskPatternCheckEvent.class));
    }

    @Test
    @DisplayName("이 턴이 방금 위험 알림을 받았으면 누적 판정은 건너뛰고, 이미 보낸 대화(선점 짐)면 판정한다(점검 L-1)")
    void riskAlertTurnSkipsPatternCheck() {
        properties.getRiskPattern().setEnabled(true);
        when(repository.findById(9L)).thenReturn(Optional.of(open(9L)));
        when(repository.claimRiskNotification(eq(9L), any())).thenReturn(1, 0);

        writer.recordTurn(9L, 1, true, new BigDecimal("0.9950"), NOW);
        verify(publisher, never()).publishEvent(any(CompanionRiskPatternCheckEvent.class));

        writer.recordTurn(9L, 2, true, new BigDecimal("0.9950"), NOW);
        verify(publisher).publishEvent(new CompanionRiskPatternCheckEvent("CW0001", NOW.toLocalDate()));
    }

    @Test
    @DisplayName("끝난 대화의 턴은 확률도 기록하지 않고 이벤트도 내지 않는다")
    void finishedSessionIgnoresProbability() {
        properties.getRiskPattern().setEnabled(true);
        CompanionSession done = open(8L);
        done.finish(CompanionEndReason.USER_STOP, CompanionMood.CALM, 3, 3, 0, false, false, null, NOW);
        when(repository.findById(8L)).thenReturn(Optional.of(done));

        writer.recordTurn(8L, 4, false, new BigDecimal("0.9900"), NOW);

        assertThat(done.getMaxRiskProb()).isNull();
        verify(publisher, never()).publishEvent(any());
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

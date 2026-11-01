package kr.silverbridge.main.domain.checkin.service;

import kr.silverbridge.main.domain.checkin.entity.CheckinEmotion;
import kr.silverbridge.main.domain.checkin.entity.CheckinEndReason;
import kr.silverbridge.main.domain.checkin.entity.CheckinResult;
import kr.silverbridge.main.domain.checkin.entity.CheckinSession;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;
import kr.silverbridge.main.domain.checkin.event.CheckinGuardianAlertEvent;
import kr.silverbridge.main.domain.checkin.repository.CheckinResultRepository;
import kr.silverbridge.main.domain.checkin.repository.CheckinSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 보호자 알림 이벤트는 위험이 "처음" 올라갈 때 한 번, 위험이면 확인 필요를 겹쳐 보내지 않는다. */
class CheckinSessionStoreTest {

    private final CheckinSessionRepository sessionRepository = mock(CheckinSessionRepository.class);
    private final CheckinResultRepository resultRepository = mock(CheckinResultRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final CheckinSessionStore store = new CheckinSessionStore(sessionRepository, resultRepository, publisher);
    private final CheckinSession session = CheckinSession.start("ck_abcdefghijklmnopqrstuvwx", "wA1b2C",
            LocalDate.of(2026, 11, 1), CheckinSlot.AT_15, "q", OffsetDateTime.now());

    @Test
    @DisplayName("턴에서 위험이 처음 올라가면 RISK 이벤트 하나, 다음 위험 턴에서는 다시 내지 않는다")
    void riskOnceOnTurn() {
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

        store.recordTurn(1L, 0, false, false, true);
        store.recordTurn(1L, 1, false, true, false);

        ArgumentCaptor<CheckinGuardianAlertEvent> event = ArgumentCaptor.forClass(CheckinGuardianAlertEvent.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue().kind()).isEqualTo(CheckinGuardianAlertEvent.Kind.RISK);
        assertThat(event.getValue().slot()).isEqualTo(CheckinSlot.AT_15);
    }

    @Test
    @DisplayName("위험 없는 정리에서 확인 필요면 ATTENTION 이벤트")
    void attentionOnFinish() {
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(resultRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        store.finish(1L, result(false, true), CheckinEndReason.USER_END, OffsetDateTime.now());

        ArgumentCaptor<CheckinGuardianAlertEvent> event = ArgumentCaptor.forClass(CheckinGuardianAlertEvent.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue().kind()).isEqualTo(CheckinGuardianAlertEvent.Kind.ATTENTION);
    }

    @Test
    @DisplayName("턴에서 이미 위험 알림이 나간 대화의 정리는 RISK·ATTENTION 모두 다시 내지 않는다")
    void noDuplicateAfterTurnRisk() {
        session.recordTurn(false, true, false);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(resultRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        store.finish(1L, result(true, true), CheckinEndReason.USER_END, OffsetDateTime.now());

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("확인 필요가 아니면 이벤트 없음")
    void nothingWhenCalm() {
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(resultRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        store.finish(1L, result(false, false), CheckinEndReason.USER_END, OffsetDateTime.now());

        verify(publisher, never()).publishEvent(any());
    }

    private CheckinResult result(boolean risk, boolean attention) {
        return CheckinResult.builder().wardId("wA1b2C").checkDate(session.getCheckDate()).slot(CheckinSlot.AT_15)
                .emotion(CheckinEmotion.CALM).riskEmergency(risk).endReason(CheckinEndReason.USER_END)
                .attention(attention).attentionReasons(List.of()).build();
    }
}

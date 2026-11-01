package kr.silverbridge.main.domain.checkin.listener;

import kr.silverbridge.main.domain.checkin.service.CheckinSettingService;
import kr.silverbridge.main.domain.connection.event.ConnectionDisconnectedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionDisconnectedEvent.DisconnectedBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 점검 L-1: 연결이 끝나면 그 보호자가 켠 안부 알림만 끈다. 실패해도 연결 해제 흐름을 막지 않는다. */
class CheckinConnectionEndedListenerTest {

    private final CheckinSettingService settingService = mock(CheckinSettingService.class);
    private final CheckinConnectionEndedListener listener = new CheckinConnectionEndedListener(settingService);

    @Test
    @DisplayName("해제된 보호자·피보호자 쌍으로 끄기를 요청한다")
    void disables() {
        listener.handleDisconnected(new ConnectionDisconnectedEvent(1L, "wA1b2C", DisconnectedBy.GUARDIAN, "gA1b2C", "wA1b2C"));
        verify(settingService).disablePromptEnabledBy("gA1b2C", "wA1b2C");
    }

    @Test
    @DisplayName("당사자가 비어 있으면 아무것도 하지 않는다")
    void missingParties() {
        listener.handleDisconnected(new ConnectionDisconnectedEvent(1L, "x", DisconnectedBy.ADMIN, null, "wA1b2C"));
        verify(settingService, never()).disablePromptEnabledBy(anyString(), anyString());
    }

    @Test
    @DisplayName("실패는 삼킨다")
    void swallowFailure() {
        when(settingService.disablePromptEnabledBy("gA1b2C", "wA1b2C")).thenThrow(new RuntimeException("db"));
        assertThatNoException().isThrownBy(() -> listener.handleDisconnected(
                new ConnectionDisconnectedEvent(1L, "wA1b2C", DisconnectedBy.WARD, "gA1b2C", "wA1b2C")));
    }

    @Test
    @DisplayName("커밋 뒤(AFTER_COMMIT) 동기 리스너다")
    void afterCommit() throws Exception {
        var method = CheckinConnectionEndedListener.class.getMethod("handleDisconnected", ConnectionDisconnectedEvent.class);
        assertThat(method.getAnnotation(TransactionalEventListener.class).phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}

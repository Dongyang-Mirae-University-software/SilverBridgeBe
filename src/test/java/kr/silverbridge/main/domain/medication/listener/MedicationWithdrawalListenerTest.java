package kr.silverbridge.main.domain.medication.listener;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.medication.service.MedicationWithdrawalService;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.websocket.WebSocketEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * MedicationWithdrawalListener 단위 테스트.
 *
 * <p>검증 축 — ① 안내는 <b>조치할 수 있는 사람</b>(남은 ACTIVE 보호자)에게만 가고 탈퇴자 본인은 제외되는가
 * ② 남은 보호자가 없으면 보내지 않는가 ③ 실패가 밖으로 새어 나가지 않는가(best-effort — 예외가 전파되면
 * 나머지 탈퇴 리스너와 purge까지 막혀 좀비 계정이 된다).</p>
 */
@ExtendWith(MockitoExtension.class)
class MedicationWithdrawalListenerTest {

    @Mock private MedicationWithdrawalService medicationWithdrawalService;
    @Mock private ConnectionService connectionService;
    @Mock private NotificationDispatcher notificationDispatcher;
    @Mock private WebSocketEventPublisher webSocketEventPublisher;
    @Mock private UserRepository userRepository;

    @InjectMocks private MedicationWithdrawalListener listener;

    private static final String WITHDRAWN_GUARDIAN_ID = "GD0001";
    private static final String REMAINING_GUARDIAN_ID = "GD0002";
    private static final String WARD_ID = "WD0001";

    private static UserWithdrawnEvent withdrawnEvent() {
        return new UserWithdrawnEvent(WITHDRAWN_GUARDIAN_ID, "127.0.0.1", "test-agent");
    }

    @Test
    @DisplayName("남은 보호자에게만 중지 안내 — 탈퇴자 본인은 수신자에서 제외된다")
    void 남은보호자에게만_안내() {
        when(medicationWithdrawalService.removeMedicationsRegisteredBy(WITHDRAWN_GUARDIAN_ID))
                .thenReturn(Map.of(WARD_ID, 3));
        // 연결 해제 리스너보다 먼저 실행되면 탈퇴자도 아직 ACTIVE 목록에 남아 있을 수 있다.
        when(connectionService.getActiveGuardianIds(WARD_ID))
                .thenReturn(List.of(WITHDRAWN_GUARDIAN_ID, REMAINING_GUARDIAN_ID));
        when(userRepository.findById(WARD_ID))
                .thenReturn(Optional.of(User.builder().id(WARD_ID).name("김영희").build()));

        listener.handleWithdrawn(withdrawnEvent());

        ArgumentCaptor<NotificationContent> contentCaptor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(
                eq(REMAINING_GUARDIAN_ID), eq(NotificationType.MEDICATION_STOPPED), contentCaptor.capture());
        assertThat(contentCaptor.getValue().body()).contains("김영희", "3건");
        assertThat(contentCaptor.getValue().data()).containsEntry("wardId", WARD_ID);

        verify(webSocketEventPublisher).sendToUser(eq(REMAINING_GUARDIAN_ID), eq("medication-stopped"), any());
        // 탈퇴자에게는 보내지 않는다.
        verify(notificationDispatcher, never()).dispatch(eq(WITHDRAWN_GUARDIAN_ID), any(), any());
    }

    @Test
    @DisplayName("남은 보호자가 없으면 안내를 보내지 않는다(약 정리는 이미 끝난 상태)")
    void 남은보호자없음_미발송() {
        when(medicationWithdrawalService.removeMedicationsRegisteredBy(WITHDRAWN_GUARDIAN_ID))
                .thenReturn(Map.of(WARD_ID, 2));
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of(WITHDRAWN_GUARDIAN_ID));

        listener.handleWithdrawn(withdrawnEvent());

        verify(notificationDispatcher, never()).dispatch(anyString(), any(), any());
        verify(webSocketEventPublisher, never()).sendToUser(anyString(), anyString(), any());
        // 수신자가 없으면 이름 조회도 하지 않는다.
        verify(userRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("정리할 약이 없으면(피보호자 탈퇴 등) 아무 알림도 보내지 않는다")
    void 정리할약없음_미발송() {
        when(medicationWithdrawalService.removeMedicationsRegisteredBy(WITHDRAWN_GUARDIAN_ID))
                .thenReturn(Map.of());

        listener.handleWithdrawn(withdrawnEvent());

        verify(connectionService, never()).getActiveGuardianIds(anyString());
        verify(notificationDispatcher, never()).dispatch(anyString(), any(), any());
    }

    @Test
    @DisplayName("정리 중 예외가 나도 밖으로 전파하지 않는다 — 나머지 탈퇴 리스너와 purge를 막으면 안 된다")
    void 실패해도_예외전파없음() {
        when(medicationWithdrawalService.removeMedicationsRegisteredBy(WITHDRAWN_GUARDIAN_ID))
                .thenThrow(new IllegalStateException("DB 순단"));

        assertThatNoException().isThrownBy(() -> listener.handleWithdrawn(withdrawnEvent()));
    }
}

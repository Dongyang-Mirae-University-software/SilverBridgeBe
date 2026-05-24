package kr.silverbridge.main.domain.notification.listener;

import kr.silverbridge.main.domain.notification.service.FcmService;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserWithdrawalFcmListenerTest {

    @Mock private FcmService fcmService;
    @InjectMocks private UserWithdrawalFcmListener listener;

    @Test
    @DisplayName("UserWithdrawnEvent 수신 시 FCM 토큰 일괄 삭제를 위임한다")
    void handleWithdrawn_FCM정리_위임() {
        listener.handleWithdrawn(new UserWithdrawnEvent("user-1", "127.0.0.1", "agent"));

        verify(fcmService).deleteAllTokens("user-1");
    }
}

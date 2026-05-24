package kr.silverbridge.main.domain.connection.listener;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserWithdrawalConnectionListenerTest {

    @Mock private ConnectionService connectionService;
    @InjectMocks private UserWithdrawalConnectionListener listener;

    @Test
    @DisplayName("UserWithdrawnEvent 수신 시 본인 연결 정리를 위임한다")
    void handleWithdrawn_연결정리_위임() {
        listener.handleWithdrawn(new UserWithdrawnEvent("user-1", "127.0.0.1", "agent"));

        verify(connectionService).tearDownConnectionsOnWithdrawal("user-1");
    }
}

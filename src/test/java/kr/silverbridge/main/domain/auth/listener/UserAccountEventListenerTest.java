package kr.silverbridge.main.domain.auth.listener;

import kr.silverbridge.main.domain.auth.repository.RefreshTokenRepository;
import kr.silverbridge.main.domain.auth.service.AccessLogService;
import kr.silverbridge.main.domain.user.event.PasswordChangedEvent;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
import kr.silverbridge.main.global.enums.AccessAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UserAccountEventListenerTest {

    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private AccessLogService accessLogService;

    @InjectMocks private UserAccountEventListener listener;

    @Test
    @DisplayName("UserWithdrawnEvent 수신 시 토큰 삭제 + WITHDRAW 접속 로그 기록")
    void handleWithdrawn_토큰삭제_및_WITHDRAW_로그() {
        UserWithdrawnEvent event = new UserWithdrawnEvent("user-1", "127.0.0.1", "test-agent");

        listener.handleWithdrawn(event);

        verify(refreshTokenRepository).deleteByUserId("user-1");
        verify(accessLogService).log("user-1", AccessAction.WITHDRAW, "127.0.0.1", "test-agent");
    }

    @Test
    @DisplayName("PasswordChangedEvent 수신 시 토큰 삭제, 접속 로그 호출 없음")
    void handlePasswordChanged_토큰삭제_only() {
        PasswordChangedEvent event = new PasswordChangedEvent("user-2");

        listener.handlePasswordChanged(event);

        verify(refreshTokenRepository).deleteByUserId("user-2");
        verifyNoInteractions(accessLogService);
    }
}

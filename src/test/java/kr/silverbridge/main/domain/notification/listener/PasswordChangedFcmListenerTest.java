package kr.silverbridge.main.domain.notification.listener;

import kr.silverbridge.main.domain.notification.service.FcmService;
import kr.silverbridge.main.domain.user.event.PasswordChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PasswordChangedFcmListenerTest {

    @Mock private FcmService fcmService;
    @InjectMocks private PasswordChangedFcmListener listener;

    @Test
    @DisplayName("PasswordChangedEvent 수신 시 그 사용자의 FCM 토큰 일괄 삭제를 위임한다 (USER-G02)")
    void handlePasswordChanged_FCM정리_위임() {
        listener.handlePasswordChanged(new PasswordChangedEvent("user-1"));

        verify(fcmService).deleteAllTokens("user-1");
    }

    @Test
    @DisplayName("FCM 정리 실패는 전파하지 않는다 - 비밀번호 변경은 이미 커밋됐다")
    void handlePasswordChanged_실패_미전파() {
        doThrow(new RuntimeException("DB 장애")).when(fcmService).deleteAllTokens("user-2");

        assertThatCode(() -> listener.handlePasswordChanged(new PasswordChangedEvent("user-2")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("동기 AFTER_COMMIT 리스너가 부르는 deleteAllTokens는 REQUIRES_NEW여야 한다 (CLAUDE.md §8 H-1)")
    void deleteAllTokens_REQUIRES_NEW() throws Exception {
        Transactional tx = FcmService.class.getMethod("deleteAllTokens", String.class)
                .getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}

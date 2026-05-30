package kr.silverbridge.main.domain.auth.listener;

import kr.silverbridge.main.domain.auth.event.KakaoRegisteredEvent;
import kr.silverbridge.main.domain.auth.service.AccessLogService;
import kr.silverbridge.main.global.enums.AccessAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class KakaoRegisterEventListenerTest {

    @Mock private AccessLogService accessLogService;

    @InjectMocks private KakaoRegisterEventListener listener;

    @Test
    @DisplayName("KakaoRegisteredEvent(AFTER_COMMIT) 수신 시 KAKAO_LOGIN 접속로그를 기록한다 — user 커밋 후이므로 FK 안전")
    void handleKakaoRegistered_KAKAO_LOGIN_로그기록() {
        KakaoRegisteredEvent event = new KakaoRegisteredEvent("kAk123", "127.0.0.1", "test-agent");

        listener.handleKakaoRegistered(event);

        verify(accessLogService).log("kAk123", AccessAction.KAKAO_LOGIN, "127.0.0.1", "test-agent");
    }
}
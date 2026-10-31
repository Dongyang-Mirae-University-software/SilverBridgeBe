package kr.silverbridge.main.domain.chat.listener;

import kr.silverbridge.main.domain.chat.client.AiChatClient;
import kr.silverbridge.main.domain.connection.event.ConnectionDisconnectedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionDisconnectedEvent.DisconnectedBy;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 연결 종료 시 보호자의 대리 상담 기록 삭제 (2026-10 전체 점검 L-13). */
@ExtendWith(MockitoExtension.class)
class AiProxyChatLogPurgeListenerTest {

    @Mock private AiChatClient aiChatClient;
    @InjectMocks private AiProxyChatLogPurgeListener listener;

    private static ConnectionDisconnectedEvent event(DisconnectedBy by) {
        return new ConnectionDisconnectedEvent(1L, "WD0001", by, "GD0001", "WD0001");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = DisconnectedBy.class, names = {"GUARDIAN", "WARD", "ADMIN"})
    @DisplayName("보호자·피보호자 해제, 관리자 해제·역할 변경 정리면 그 쌍의 대리 상담 기록 삭제를 요청한다")
    void 연결종료_삭제요청(DisconnectedBy by) {
        listener.handleDisconnected(event(by));

        verify(aiChatClient).deleteProxyLogs("GD0001", "WD0001");
    }

    @Test
    @DisplayName("탈퇴 정리는 건너뛴다 - 탈퇴한 쪽의 전체 정리가 이미 같은 기록을 지운다")
    void 탈퇴는_건너뜀() {
        listener.handleDisconnected(event(DisconnectedBy.WITHDRAWN));

        verifyNoInteractions(aiChatClient);
    }

    @Test
    @DisplayName("AI 호출이 실패해도 예외를 밖으로 내보내지 않는다 - 연결 해제를 막지 않는다")
    void 실패는_삼킨다() {
        doThrow(new CustomException(ErrorCode.CHAT_UNAVAILABLE)).when(aiChatClient).deleteProxyLogs("GD0001", "WD0001");

        assertThatNoException().isThrownBy(() -> listener.handleDisconnected(event(DisconnectedBy.GUARDIAN)));
    }
}

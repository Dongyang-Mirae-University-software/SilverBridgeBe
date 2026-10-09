package kr.silverbridge.main.domain.chat.listener;

import kr.silverbridge.main.domain.chat.client.AiChatClient;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AiMemberDataPurgeListenerTest {

    private static final UserWithdrawnEvent EVENT = new UserWithdrawnEvent("aB3x9Z", "1.2.3.4", "ua");

    @Mock
    private AiChatClient aiChatClient;
    @InjectMocks
    private AiMemberDataPurgeListener listener;

    @Test
    @DisplayName("탈퇴한 회원의 ID로 AI 상담 기록과 예약 API 키 삭제를 모두 요청한다")
    void 탈퇴하면_삭제_요청() {
        listener.handleWithdrawn(EVENT);

        verify(aiChatClient).deleteLogs("aB3x9Z");
        verify(aiChatClient).deleteReservationCredential("aB3x9Z");
    }

    @Test
    @DisplayName("AI 호출이 실패해도 예외를 밖으로 내보내지 않는다 - 탈퇴 파이프라인을 막지 않는다")
    void 실패는_삼킨다() {
        doThrow(new CustomException(ErrorCode.CHAT_UNAVAILABLE)).when(aiChatClient).deleteLogs("aB3x9Z");
        doThrow(new CustomException(ErrorCode.CHAT_UNAVAILABLE)).when(aiChatClient).deleteReservationCredential("aB3x9Z");

        assertThatNoException().isThrownBy(() -> listener.handleWithdrawn(EVENT));
    }

    @Test
    @DisplayName("상담 기록 삭제가 실패해도 예약 API 키 삭제는 계속 시도한다 - 항목별 독립")
    void 한쪽_실패가_다른쪽을_막지_않는다() {
        doThrow(new CustomException(ErrorCode.CHAT_UNAVAILABLE)).when(aiChatClient).deleteLogs("aB3x9Z");

        listener.handleWithdrawn(EVENT);

        verify(aiChatClient).deleteReservationCredential("aB3x9Z");
    }

    @Test
    @DisplayName("예약 API 키 삭제가 실패해도 상담 기록 삭제는 이미 요청됐다")
    void 예약키_실패해도_상담기록은_요청됨() {
        doThrow(new CustomException(ErrorCode.CHAT_UNAVAILABLE)).when(aiChatClient).deleteReservationCredential("aB3x9Z");

        assertThatNoException().isThrownBy(() -> listener.handleWithdrawn(EVENT));

        verify(aiChatClient).deleteLogs("aB3x9Z");
    }

    @Test
    @DisplayName("커밋 이후(AFTER_COMMIT)에만, 전용 executor에서 비동기로 동작한다")
    void 커밋_후_비동기() throws Exception {
        Method method = AiMemberDataPurgeListener.class.getDeclaredMethod("handleWithdrawn", UserWithdrawnEvent.class);

        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(method.getAnnotation(Async.class).value()).isEqualTo("aiPurgeExecutor");
    }
}

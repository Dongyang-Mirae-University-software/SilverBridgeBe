package kr.silverbridge.main.domain.game.listener;

import kr.silverbridge.main.domain.game.client.AiGameClient;
import kr.silverbridge.main.domain.user.event.UserRoleChangedEvent;
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
class GameRecordPurgeListenerTest {

    private static final UserWithdrawnEvent EVENT = new UserWithdrawnEvent("aB3x9Z", "1.2.3.4", "ua");

    @Mock private AiGameClient aiGameClient;
    @InjectMocks private GameRecordPurgeListener listener;

    @Test
    @DisplayName("탈퇴한 회원의 게임 번호(FE toGameUserId 와 같은 값)로 AI 게임 기록 삭제를 요청한다")
    void 탈퇴하면_게임기록_삭제_요청() {
        listener.handleWithdrawn(EVENT);

        verify(aiGameClient).deleteRecords(1455350745L);
    }

    @Test
    @DisplayName("AI 삭제가 실패해도 예외를 밖으로 내보내지 않는다 - 탈퇴 파이프라인을 막지 않는다")
    void 실패는_삼킨다() {
        doThrow(new CustomException(ErrorCode.GAME_UNAVAILABLE)).when(aiGameClient).deleteRecords(1455350745L);

        assertThatNoException().isThrownBy(() -> listener.handleWithdrawn(EVENT));
    }

    @Test
    @DisplayName("커밋 이후(AFTER_COMMIT)에만, 전용 aiPurgeExecutor 에서 비동기로 동작한다")
    void 커밋_후_비동기() throws Exception {
        Method method = GameRecordPurgeListener.class.getDeclaredMethod("handleWithdrawn", UserWithdrawnEvent.class);

        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(method.getAnnotation(Async.class).value()).isEqualTo("aiPurgeExecutor");
    }

    @Test
    @DisplayName("L-1: 관리자가 역할을 바꾸면 그 회원의 게임 번호로 AI 게임 기록 삭제를 요청한다(역할을 가리지 않는다)")
    void 역할_변경이면_게임기록_삭제_요청() {
        listener.handleRoleChanged(new UserRoleChangedEvent("aB3x9Z"));

        verify(aiGameClient).deleteRecords(1455350745L);
    }

    @Test
    @DisplayName("L-1: 역할 변경 때 AI 삭제가 실패해도 예외를 밖으로 내보내지 않는다(best-effort)")
    void 역할_변경_실패는_삼킨다() {
        doThrow(new CustomException(ErrorCode.GAME_UNAVAILABLE)).when(aiGameClient).deleteRecords(1455350745L);

        assertThatNoException().isThrownBy(() -> listener.handleRoleChanged(new UserRoleChangedEvent("aB3x9Z")));
    }

    @Test
    @DisplayName("L-1: 역할 변경 처리도 커밋 이후(AFTER_COMMIT), 전용 aiPurgeExecutor 에서 비동기로 동작한다")
    void 역할_변경_커밋_후_비동기() throws Exception {
        Method method = GameRecordPurgeListener.class.getDeclaredMethod("handleRoleChanged", UserRoleChangedEvent.class);

        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(method.getAnnotation(Async.class).value()).isEqualTo("aiPurgeExecutor");
    }
}

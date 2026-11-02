package kr.silverbridge.main.domain.user;

import kr.silverbridge.main.domain.chat.client.AiChatClient;
import kr.silverbridge.main.domain.chat.listener.AiMemberDataPurgeListener;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
import kr.silverbridge.main.global.config.AsyncConfig;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 탈퇴 이벤트가 실제 Spring 컨텍스트에서 AI 회원 데이터 삭제 리스너에 도달하는가 (audit-chat-log-purge-on-withdraw M-1, 2026-10-09).
 *
 * <p>리스너의 애너테이션 조합({@code @Async} + {@code @TransactionalEventListener(AFTER_COMMIT)})은 단위 테스트가 고정하지만,
 * "탈퇴 트랜잭션이 커밋되면 비동기 스레드에서 실제로 호출되는가"는 컨텍스트가 있어야 보인다. 이벤트가 트랜잭션 밖에서
 * 발행되거나 {@code @EnableAsync}·executor 이름이 어긋나면 호출이 조용히 사라진다(AFTER_COMMIT은 트랜잭션이 없으면 버려진다).</p>
 *
 * <p>검증: ① 커밋하면 호출된다(상담 기록 + 예약 API 키, 요청 스레드가 아닌 곳에서) ② 롤백하면 호출되지 않는다.
 * AI 호출은 목으로 두고, 실제 {@link AsyncConfig}의 executor와 실제 리스너를 쓴다. 백엔드 DB는 쓰지 않는다.</p>
 */
@Import({AsyncConfig.class, AiMemberDataPurgeListener.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WithdrawalAiPurgeIntegrationTest extends PostgresIntegrationTest {

    private static final String USER_ID = "WAP001";

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationEventPublisher eventPublisher;

    @MockitoBean private AiChatClient aiChatClient;

    @Test
    @DisplayName("탈퇴 트랜잭션이 커밋되면 비동기로 AI 상담 기록과 예약 API 키 삭제가 요청된다")
    void 커밋하면_삭제가_요청된다() {
        String requestThread = Thread.currentThread().getName();
        AtomicReference<String> calledOn = new AtomicReference<>();
        doAnswer(invocation -> {
            calledOn.set(Thread.currentThread().getName());
            return null;
        }).when(aiChatClient).deleteLogs(USER_ID);

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                eventPublisher.publishEvent(new UserWithdrawnEvent(USER_ID, "127.0.0.1", "test")));

        verify(aiChatClient, timeout(5_000)).deleteLogs(USER_ID);
        verify(aiChatClient, timeout(5_000)).deleteReservationCredential(USER_ID);
        assertThat(calledOn.get())
                .as("전용 executor 스레드에서 실행(탈퇴 요청 스레드를 붙들지 않는다)")
                .startsWith("ai-purge-")
                .isNotEqualTo(requestThread);
    }

    @Test
    @DisplayName("탈퇴 트랜잭션이 롤백되면 AI 삭제를 요청하지 않는다 - 지우지 않은 회원의 기록을 지우면 안 된다")
    void 롤백하면_요청하지_않는다() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            eventPublisher.publishEvent(new UserWithdrawnEvent(USER_ID, "127.0.0.1", "test"));
            status.setRollbackOnly();
        });

        verify(aiChatClient, after(1_000).never()).deleteLogs(USER_ID);
        verify(aiChatClient, never()).deleteReservationCredential(USER_ID);
    }
}

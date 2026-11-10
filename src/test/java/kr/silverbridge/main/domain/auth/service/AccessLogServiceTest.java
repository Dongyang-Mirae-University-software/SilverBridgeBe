package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.entity.AccessLog;
import kr.silverbridge.main.domain.auth.repository.AccessLogRepository;
import kr.silverbridge.main.global.enums.AccessAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AccessLogServiceTest {

    @Mock private AccessLogRepository accessLogRepository;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;

    private AccessLogService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        service = new AccessLogService(accessLogRepository, transactionManager);
    }

    @Test
    @DisplayName("userAgent 500자·IP 50자 초과는 컬럼 길이로 잘라 저장한다")
    void truncates() {
        service.log("u1", AccessAction.LOGIN, "1".repeat(80), "x".repeat(900));

        ArgumentCaptor<AccessLog> captor = ArgumentCaptor.forClass(AccessLog.class);
        verify(accessLogRepository).save(captor.capture());
        assertThat(captor.getValue().getIpAddress()).hasSize(50);
        assertThat(captor.getValue().getUserAgent()).hasSize(500);
    }

    @Test
    @DisplayName("서로게이트 쌍 중간에서 자르지 않는다")
    void doesNotSplitSurrogate() {
        // 499자 + 이모지(2칸) → 500 경계가 쌍 중간이므로 499자로 줄인다
        String ua = "x".repeat(499) + "😀";

        service.log("u1", AccessAction.LOGIN, "1.1.1.1", ua);

        ArgumentCaptor<AccessLog> captor = ArgumentCaptor.forClass(AccessLog.class);
        verify(accessLogRepository).save(captor.capture());
        assertThat(captor.getValue().getUserAgent()).hasSize(499);
    }

    @Test
    @DisplayName("짧은 값·null 은 그대로")
    void keepsShortAndNull() {
        service.log("u1", AccessAction.LOGIN, null, "Mozilla");

        ArgumentCaptor<AccessLog> captor = ArgumentCaptor.forClass(AccessLog.class);
        verify(accessLogRepository).save(captor.capture());
        assertThat(captor.getValue().getIpAddress()).isNull();
        assertThat(captor.getValue().getUserAgent()).isEqualTo("Mozilla");
    }

    @Test
    @DisplayName("저장 실패는 호출자에게 전파되지 않는다(롤백 처리됨)")
    void swallowsSaveFailure() {
        doThrow(new DataIntegrityViolationException("boom")).when(accessLogRepository).save(any());

        assertThatNoException().isThrownBy(() -> service.log("u1", AccessAction.LOGIN, "1.1.1.1", "ua"));
        verify(transactionManager).rollback(transactionStatus);
    }

    @Test
    @DisplayName("트랜잭션 안에서는 그 자리에서 쓰지 않고, 트랜잭션이 끝난 뒤 다른 스레드에서 쓴다 (전체 점검 2026-11 2차 H-1)")
    void defersUntilTransactionCompletes() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.log("u1", AccessAction.LOGIN, "1.1.1.1", "ua");

            // 호출 스레드는 두 번째 커넥션을 요구하지 않는다
            verify(transactionManager, never()).getTransaction(any(TransactionDefinition.class));
            verify(accessLogRepository, never()).save(any());

            // 롤백으로 끝나도 기록은 남는다(재사용 감지처럼 예외로 끝나는 경로)
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            String caller = Thread.currentThread().getName();
            AtomicReference<String> writerThread = new AtomicReference<>();
            lenient().when(accessLogRepository.save(any())).thenAnswer(invocation -> {
                writerThread.set(Thread.currentThread().getName());
                return invocation.getArgument(0);
            });
            synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            verify(accessLogRepository, timeout(2000)).save(any());
            assertThat(writerThread.get()).startsWith("access-log-").isNotEqualTo(caller);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    @DisplayName("logNow는 트랜잭션 안에서도 바로 쓴다 - 탈퇴처럼 뒤따르는 삭제보다 먼저 남아야 하는 기록")
    void logNowWritesImmediately() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.logNow("u1", AccessAction.WITHDRAW, "1.1.1.1", "ua");

            verify(accessLogRepository).save(any());
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    @DisplayName("커밋 시점 실패도 전파되지 않는다")
    void swallowsCommitFailure() {
        doThrow(new TransactionSystemException("commit failed")).when(transactionManager).commit(any());

        assertThatNoException().isThrownBy(() -> service.log("u1", AccessAction.LOGOUT));
    }
}

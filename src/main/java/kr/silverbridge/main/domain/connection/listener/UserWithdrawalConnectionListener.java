package kr.silverbridge.main.domain.connection.listener;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 회원 탈퇴(UserWithdrawnEvent) 시 본인이 참여한 연결을 정리하는 리스너 (D-USER-3).
 * <p>
 * ACTIVE → DISCONNECTED(상대에게 해제 알림) / PENDING → CANCELLED(무알림).
 * 의존 방향: user(이벤트 발행) → connection(수신). 역방향 import 없음.
 * {@link TransactionPhase#AFTER_COMMIT} 에서만 동작하여 탈퇴 트랜잭션이 롤백되면 연결도 정리되지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserWithdrawalConnectionListener {

    private final ConnectionService connectionService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleWithdrawn(UserWithdrawnEvent event) {
        // best-effort — 예외가 새어 나가면 나머지 리스너·purge까지 막혀 좀비 계정(M-S1-1)이 된다.
        // 실패해도 connections 행은 purge의 FK CASCADE가 삭제한다(상대 알림만 유실될 수 있음).
        try {
            connectionService.tearDownConnectionsOnWithdrawal(event.userId());
        } catch (RuntimeException e) {
            log.error("[WITHDRAW] 연결 정리 실패 — purge CASCADE가 회수 예정 userId={}", event.userId(), e);
        }
    }
}

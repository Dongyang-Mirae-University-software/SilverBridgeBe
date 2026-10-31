package kr.silverbridge.main.domain.chat.listener;

import kr.silverbridge.main.domain.chat.client.AiChatClient;
import kr.silverbridge.main.domain.connection.event.ConnectionDisconnectedEvent;
import kr.silverbridge.main.domain.connection.event.ConnectionDisconnectedEvent.DisconnectedBy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 연결이 끝나면 그 보호자가 피보호자를 대신해 상담한 AI 기록을 지운다(2026-10 전체 점검 L-13).
 *
 * <p>대리 상담 기록에는 피보호자 이름·전화·건강 대화·예약 초안이 들어 있다. 지금까지는 피보호자가 탈퇴할 때만 지워져
 * ({@link AiMemberDataPurgeListener}, AI #129), 연결을 끊거나 역할이 바뀌어 더는 보호자가 아닌 사람의 기록에 남았다.
 * 연결 종료 다섯 경로(보호자·피보호자 해제, 역할 변경 정리, 관리자 강제 해제, 탈퇴 정리)가 모두 이 이벤트를 발행한다.
 * <b>탈퇴는 건너뛴다</b> - 탈퇴한 쪽의 전체 정리가 이미 같은 기록을 지운다.</p>
 *
 * <p>탈퇴 정리와 같은 방식이다: 비동기 AFTER_COMMIT + 전용 {@code aiPurgeExecutor}(AI 호출이 해제 응답을 붙들지 않게),
 * best-effort(실패는 userId와 예외 클래스명만 WARN, 재시도 없음). 관리자 강제 해제는 양쪽 알림용으로 이벤트가 두 번
 * 발행돼 두 번 부르지만 AI 삭제가 멱등이라 둔다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiProxyChatLogPurgeListener {

    private final AiChatClient aiChatClient;

    @Async("aiPurgeExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleDisconnected(ConnectionDisconnectedEvent event) {
        if (event.disconnectedBy() == DisconnectedBy.WITHDRAWN
                || event.guardianId() == null || event.wardId() == null) {
            return;
        }
        try {
            aiChatClient.deleteProxyLogs(event.guardianId(), event.wardId());
            log.info("[AI-PURGE] 연결 종료로 대리 상담 기록 삭제 요청 완료: guardianId={}, wardId={}",
                    event.guardianId(), event.wardId());
        } catch (RuntimeException e) {
            log.warn("[AI-PURGE-FAILED] 대리 상담 기록 삭제 실패 - AI 서버에 남을 수 있다: guardianId={}, wardId={}, error={}",
                    event.guardianId(), event.wardId(), e.getClass().getSimpleName());
        }
    }
}

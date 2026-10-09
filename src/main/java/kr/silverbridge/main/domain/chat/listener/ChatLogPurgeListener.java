package kr.silverbridge.main.domain.chat.listener;

import kr.silverbridge.main.domain.chat.client.AiChatClient;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 회원 탈퇴 시 AI 서버에 남은 상담 기록을 지운다(2026-10-09, 피보호자 챗 점검 M-1).
 *
 * <p>탈퇴는 hard delete이고 "탈퇴자가 남긴 데이터를 붙들지 않는다"가 기본이다. 상담 기록은 백엔드 DB가 아니라 AI 서버에 있어
 * 회원 행 삭제(CASCADE)가 닿지 않는다. 보호자·피보호자 모두 같은 사용자 ID라 한 번에 지운다.</p>
 *
 * <p><b>비동기 AFTER_COMMIT</b>이다. 이벤트가 {@code userId}를 들고 있어 회원 행이 필요 없으므로(동기 리스너가 필요했던 클립과
 * 다르다) AI 호출(최대 15초)이 탈퇴 응답을 붙들지 않게 전용 {@code chatPurgeExecutor}로 넘긴다. 백엔드 DB를 쓰지 않아
 * {@code REQUIRES_NEW}도 필요 없다.</p>
 *
 * <p><b>best-effort</b>: 실패해도 탈퇴·나머지 리스너·purge는 계속된다. 실패 로그는 userId와 예외 클래스명만 남긴다(상담 내용·AI
 * 오류 문구 금지). 스윕 purge(리스너를 거치지 않음)나 AI 장애로 놓친 건은 AI에 남는다 - 수용한 한계(rules 파일 참조).
 * 킬 스위치 {@code chat.relay.enabled}는 전송만 멈추므로 삭제에는 영향이 없다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatLogPurgeListener {

    private final AiChatClient aiChatClient;

    @Async("chatPurgeExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleWithdrawn(UserWithdrawnEvent event) {
        try {
            aiChatClient.deleteLogs(event.userId());
            log.info("[CHAT-PURGE] 탈퇴로 AI 상담 기록 삭제 요청 완료: userId={}", event.userId());
        } catch (RuntimeException e) {
            log.warn("[CHAT-PURGE-FAILED] 탈퇴 챗 기록 삭제 실패 - AI 서버에 기록이 남을 수 있다: userId={}, error={}",
                    event.userId(), e.getClass().getSimpleName());
        }
    }
}

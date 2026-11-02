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
 * 회원 탈퇴 시 AI 서버에 남은 회원 데이터를 지운다 - 상담 기록(2026-10-09, 피보호자 챗 점검 M-1)과 예약 API 키(같은 날 M-2).
 *
 * <p>탈퇴는 hard delete이고 "탈퇴자가 남긴 데이터를 붙들지 않는다"가 기본이다. 이 데이터는 백엔드 DB가 아니라 AI 서버에 있어
 * 회원 행 삭제(CASCADE)가 닿지 않는다. 보호자·피보호자 모두 같은 사용자 ID라 한 번에 지운다.</p>
 *
 * <p><b>비동기 AFTER_COMMIT</b>이다. 이벤트가 {@code userId}를 들고 있어 회원 행이 필요 없으므로(동기 리스너가 필요했던 클립과
 * 다르다) AI 호출(최대 15초씩)이 탈퇴 응답을 붙들지 않게 전용 {@code aiPurgeExecutor}로 넘긴다. 백엔드 DB를 쓰지 않아
 * {@code REQUIRES_NEW}도 필요 없다.</p>
 *
 * <p><b>best-effort, 항목별 독립</b>: 한 항목이 실패해도 다른 항목은 계속 지운다(상담 기록 삭제가 막혀도 예약 API 키는
 * 지워야 한다). 실패해도 탈퇴·나머지 리스너·purge는 계속된다. 실패 로그는 userId와 예외 클래스명만 남긴다(상담 내용·키·AI
 * 오류 문구 금지). 스윕 purge(리스너를 거치지 않음)나 AI 장애로 놓친 건은 AI에 남는다 - 수용한 한계(rules 파일 참조).
 * 킬 스위치 {@code chat.relay.enabled}는 전송만 멈추므로 삭제에는 영향이 없다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiMemberDataPurgeListener {

    private final AiChatClient aiChatClient;

    @Async("aiPurgeExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleWithdrawn(UserWithdrawnEvent event) {
        purge("chat-logs", event.userId(), () -> aiChatClient.deleteLogs(event.userId()));
        purge("reservation-credential", event.userId(), () -> aiChatClient.deleteReservationCredential(event.userId()));
    }

    private void purge(String item, String userId, Runnable call) {
        try {
            call.run();
            log.info("[AI-PURGE] 탈퇴로 AI 회원 데이터 삭제 요청 완료: item={}, userId={}", item, userId);
        } catch (RuntimeException e) {
            log.warn("[AI-PURGE-FAILED] 탈퇴 AI 회원 데이터 삭제 실패 - AI 서버에 남을 수 있다: item={}, userId={}, error={}",
                    item, userId, e.getClass().getSimpleName());
        }
    }
}

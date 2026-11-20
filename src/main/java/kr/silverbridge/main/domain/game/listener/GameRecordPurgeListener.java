package kr.silverbridge.main.domain.game.listener;

import kr.silverbridge.main.domain.game.client.AiGameClient;
import kr.silverbridge.main.domain.game.service.GameUserIds;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 회원 탈퇴 시 AI 서버의 게임 기록(진행·풀이)을 지운다 (2026-11-20, 게임 중계 점검 M-1).
 *
 * <p>게임 기록은 AI 서버에 게임 번호({@link GameUserIds})로 쌓여 회원 행 삭제가 닿지 않는다. 상담 기록 삭제
 * ({@code AiMemberDataPurgeListener})와 같은 방식이다: <b>비동기 AFTER_COMMIT</b>({@code aiPurgeExecutor}), 이벤트의
 * {@code userId}만 쓰고 백엔드 DB는 쓰지 않는다(REQUIRES_NEW 불필요), best-effort - 실패는 {@code [AI-PURGE-FAILED]} WARN으로
 * 남기고 탈퇴를 막지 않는다. 옛 AI 서버(삭제 경로 없음)면 404가 같은 WARN으로 남는다.</p>
 *
 * <p>역할을 가리지 않고 모든 탈퇴에 요청한다 - 이벤트에 역할이 없고 회원 행은 곧바로 삭제된다. 보호자는 게임을 하지 않아
 * 보통 0건이다. 다른 회원과 게임 번호가 우연히 같으면 그 기록도 지워진다(31비트 번호 충돌, policy-game L-1과 같은 수용 한계).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GameRecordPurgeListener {

    private final AiGameClient aiGameClient;

    @Async("aiPurgeExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleWithdrawn(UserWithdrawnEvent event) {
        try {
            aiGameClient.deleteRecords(GameUserIds.of(event.userId()));
            log.info("[AI-PURGE] 탈퇴로 AI 회원 데이터 삭제 요청 완료: item=game-records, userId={}", event.userId());
        } catch (RuntimeException e) {
            log.warn("[AI-PURGE-FAILED] 탈퇴 AI 회원 데이터 삭제 실패 - AI 서버에 남을 수 있다: item=game-records, userId={}, error={}",
                    event.userId(), e.getClass().getSimpleName());
        }
    }
}

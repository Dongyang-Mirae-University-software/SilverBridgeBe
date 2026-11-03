package kr.silverbridge.main.domain.companion.listener;

import kr.silverbridge.main.domain.companion.entity.CompanionPurgeRequest;
import kr.silverbridge.main.domain.companion.repository.CompanionPurgeRequestRepository;
import kr.silverbridge.main.domain.companion.service.CompanionPurgeService;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 피보호자 탈퇴 시 AI 쪽 말벗 데이터 삭제(결정 C, 2026-11-04).
 *
 * <p><b>BEFORE_COMMIT</b>에 삭제 대기열에 넣는다 - 탈퇴 트랜잭션과 함께 커밋되므로 탈퇴가 성공하면 대기열에도 반드시 있다.
 * 회원 행이 이 시점에는 살아 있어 역할을 볼 수 있다(피보호자만 넣는다). 백엔드 결과({@code companion_session})는
 * 회원 행 삭제의 FK CASCADE가 지운다.</p>
 *
 * <p>그 뒤 <b>AFTER_COMMIT 비동기</b>({@code aiPurgeExecutor})로 한 번 바로 시도하고, 실패하면 매시 재시도가 맡는다
 * ({@code AiMemberDataPurgeListener}와 같은 풀 - 탈퇴 응답을 붙들지 않는다). 대기열이 있어 스윕 purge(리스너를 거치지
 * 않음)나 AI 장애에도 빠지지 않는다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompanionWithdrawalListener {

    private final UserRepository userRepository;
    private final CompanionPurgeRequestRepository purgeRequestRepository;
    private final CompanionPurgeService purgeService;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void enqueue(UserWithdrawnEvent event) {
        // 여기서 예외가 새면 탈퇴 자체가 롤백된다 - 남기고 넘어간다(대기열을 놓친 건은 AI 쪽에 남는다, 로그로 추적)
        try {
            boolean ward = userRepository.findById(event.userId())
                    .map(user -> user.getRole() == Role.WARD).orElse(false);
            if (ward && !purgeRequestRepository.existsById(event.userId())) {
                purgeRequestRepository.save(CompanionPurgeRequest.of(event.userId(),
                        OffsetDateTime.now(ZoneId.of("Asia/Seoul"))));
            }
        } catch (RuntimeException e) {
            log.error("[COMPANION-PURGE-FAILED] 삭제 대기열 등록 실패 - AI 쪽 말벗 데이터가 남을 수 있다: wardId={}, error={}",
                    event.userId(), e.getClass().getSimpleName());
        }
    }

    @Async("aiPurgeExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void purge(UserWithdrawnEvent event) {
        try {
            if (purgeRequestRepository.existsById(event.userId())) {
                purgeService.tryPurge(event.userId());
            }
        } catch (RuntimeException e) {
            log.warn("[COMPANION-PURGE-FAILED] 탈퇴 직후 삭제 시도 오류 - 재시도 예정: wardId={}, error={}",
                    event.userId(), e.getClass().getSimpleName());
        }
    }
}

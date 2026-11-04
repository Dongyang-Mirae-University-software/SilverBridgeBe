package kr.silverbridge.main.domain.companion.listener;

import kr.silverbridge.main.domain.companion.entity.CompanionPurgeRequest;
import kr.silverbridge.main.domain.companion.repository.CompanionPromptLogRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionPurgeRequestRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import kr.silverbridge.main.domain.companion.service.CompanionPurgeService;
import kr.silverbridge.main.domain.user.event.UserRoleChangedEvent;
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
 * 피보호자 탈퇴·역할 변경 시 말벗 데이터 정리(결정 C, 2026-11-04 / 역할 변경은 점검 M-1).
 *
 * <p><b>BEFORE_COMMIT</b>에 삭제 대기열에 넣는다 - 탈퇴·역할 변경 트랜잭션과 함께 커밋되므로 성공하면 대기열에도 반드시 있다.
 * 회원 행이 이 시점에는 살아 있어 역할을 볼 수 있다. 탈퇴의 백엔드 결과({@code companion_session})는 회원 행 삭제의
 * FK CASCADE가, 역할 변경은 이 리스너가 지운다.</p>
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
    private final CompanionSessionRepository sessionRepository;
    private final CompanionPromptLogRepository promptLogRepository;
    private final CompanionPurgeRequestRepository purgeRequestRepository;
    private final CompanionPurgeService purgeService;

    /** 탈퇴 - 피보호자이거나 말벗 기록이 있으면 대기열에 넣는다(역할이 바뀐 옛 피보호자 포함, 점검 M-1). */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void enqueue(UserWithdrawnEvent event) {
        // 여기서 예외가 새면 탈퇴 자체가 롤백된다 - 남기고 넘어간다(대기열을 놓친 건은 AI 쪽에 남는다, 로그로 추적)
        try {
            boolean ward = userRepository.findById(event.userId())
                    .map(user -> user.getRole() == Role.WARD).orElse(false);
            if (ward || sessionRepository.existsByWardId(event.userId())) {
                enqueuePurge(event.userId());
            }
        } catch (RuntimeException e) {
            log.error("[COMPANION-PURGE-FAILED] 삭제 대기열 등록 실패 - AI 쪽 말벗 데이터가 남을 수 있다: wardId={}, error={}",
                    event.userId(), e.getClass().getSimpleName());
        }
    }

    /**
     * 관리자 역할 변경(피보호자 → 보호자) - 말벗 기록·질문 안내 기록을 지우고 AI 삭제 대기열에 넣는다(2026-11-04 점검 M-1).
     * 보호자는 말벗 API를 쓸 수 없어 기록이 고아가 되고, 나중에 보호자로 탈퇴하면 AI 쪽 데이터가 영구히 남는다
     * (카메라·복약을 함께 정리하는 역할 변경 원칙과 같다). 역할 변경 트랜잭션 안(BEFORE_COMMIT)이라 함께 커밋·롤백된다.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void clearOnRoleChange(UserRoleChangedEvent event) {
        try {
            boolean stillWard = userRepository.findById(event.userId())
                    .map(user -> user.getRole() == Role.WARD).orElse(true);
            if (stillWard) {
                return;
            }
            if (!sessionRepository.existsByWardId(event.userId())) {
                promptLogRepository.deleteAllByWardId(event.userId());
                return;
            }
            int sessions = sessionRepository.deleteAllByWardId(event.userId());
            promptLogRepository.deleteAllByWardId(event.userId());
            enqueuePurge(event.userId());
            log.info("[COMPANION] 역할 변경으로 말벗 기록 정리: userId={}, sessions={}", event.userId(), sessions);
        } catch (RuntimeException e) {
            log.error("[COMPANION-PURGE-FAILED] 역할 변경 말벗 정리 실패 - 기록이 남을 수 있다: userId={}, error={}",
                    event.userId(), e.getClass().getSimpleName());
        }
    }

    @Async("aiPurgeExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void purge(UserWithdrawnEvent event) {
        purgeNow(event.userId(), "탈퇴");
    }

    @Async("aiPurgeExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void purgeAfterRoleChange(UserRoleChangedEvent event) {
        purgeNow(event.userId(), "역할 변경");
    }

    private void purgeNow(String userId, String cause) {
        try {
            if (purgeRequestRepository.existsById(userId)) {
                purgeService.tryPurge(userId);
            }
        } catch (RuntimeException e) {
            log.warn("[COMPANION-PURGE-FAILED] {} 직후 삭제 시도 오류 - 재시도 예정: wardId={}, error={}",
                    cause, userId, e.getClass().getSimpleName());
        }
    }

    private void enqueuePurge(String userId) {
        if (!purgeRequestRepository.existsById(userId)) {
            purgeRequestRepository.save(CompanionPurgeRequest.of(userId, OffsetDateTime.now(ZoneId.of("Asia/Seoul"))));
        }
    }
}

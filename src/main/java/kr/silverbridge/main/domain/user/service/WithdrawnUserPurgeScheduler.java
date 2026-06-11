package kr.silverbridge.main.domain.user.service;

import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 탈퇴 도중 멈춘(좀비) 계정을 주기적으로 회수하는 스케줄러 (M-S1-1).
 * <p>
 * 탈퇴는 withdraw(INACTIVE 전환) 커밋 → AFTER_COMMIT 리스너 → purge(영구 삭제) 순의 2단계라,
 * 사이에서 배포 재시작·인프라 순단이 끼면 INACTIVE 행이 남는다. 이 상태는 재로그인(INACTIVE)·
 * 탈퇴 재시도(토큰 무효화)·재가입(이메일/전화 잔존)이 모두 막히는 자가 복구 불가 상태다.
 * <p>
 * INACTIVE를 만드는 경로는 탈퇴가 유일하므로(불변식 — domain-security-policy.md), grace 기간이
 * 지난 INACTIVE 행은 전부 purge 대상으로 간주해 안전하다. {@code purgeWithdrawnUser}는 멱등이라
 * 정상 탈퇴 흐름과 경합해도 무해하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawnUserPurgeScheduler {

    /** INACTIVE 전환(updated_at) 후 이 시간이 지나면 좀비로 판정 — 정상 purge는 수 초 내 끝난다 */
    private static final Duration PURGE_GRACE = Duration.ofMinutes(10);

    private final UserRepository userRepository;
    private final UserService userService;

    // 10분 주기 — 좀비 발생 시 최대 grace+주기(~20분) 내 재가입 가능 상태로 회복
    @Scheduled(fixedDelay = 600_000)
    public void purgeStuckWithdrawnUsers() {
        List<User> stuck = userRepository.findAllByStatusAndUpdatedAtBefore(
                Status.INACTIVE, OffsetDateTime.now().minus(PURGE_GRACE));
        for (User user : stuck) {
            try {
                userService.purgeWithdrawnUser(user.getId());
                // 스윕 경유 purge는 탈퇴 리스너(상대 알림·WITHDRAW 접속로그)를 거치지 않으므로 WARN으로 흔적을 남긴다
                log.warn("[WITHDRAW-SWEEP] 탈퇴 잔여 계정 회수 userId={}", user.getId());
            } catch (RuntimeException e) {
                log.error("[WITHDRAW-SWEEP] 잔여 계정 회수 실패, 다음 주기에 재시도 userId={}", user.getId(), e);
            }
        }
    }
}

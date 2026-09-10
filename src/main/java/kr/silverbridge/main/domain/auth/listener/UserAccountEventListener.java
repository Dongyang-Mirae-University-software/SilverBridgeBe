package kr.silverbridge.main.domain.auth.listener;

import kr.silverbridge.main.domain.auth.repository.RefreshTokenRepository;
import kr.silverbridge.main.domain.auth.service.AccessLogService;
import kr.silverbridge.main.domain.user.event.PasswordChangedEvent;
import kr.silverbridge.main.domain.user.event.UserRestrictedEvent;
import kr.silverbridge.main.domain.user.event.UserRoleChangedEvent;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
import kr.silverbridge.main.global.enums.AccessAction;
import kr.silverbridge.main.global.jwt.JwtProperties;
import kr.silverbridge.main.global.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.TimeUnit;

/**
 * user 도메인 이벤트를 받아 토큰/접속로그 등 인증 관련 부수 효과를 처리한다.
 * 의존 방향: user(이벤트 발행) → auth(리스너 처리). 역방향 import 없음.
 *
 * AFTER_COMMIT 단계에서만 실행되어, 외부 트랜잭션이 롤백된 경우엔 부수 효과가 발생하지 않는다.
 * 이로써 user 비활성화는 실패했는데 토큰만 삭제되는 비정합 상태를 방지한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserAccountEventListener {

    private final RefreshTokenRepository refreshTokenRepository;
    private final AccessLogService accessLogService;
    private final StringRedisTemplate redisTemplate;
    private final JwtProperties jwtProperties;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleWithdrawn(UserWithdrawnEvent event) {
        // 탈퇴 정리는 best-effort — 여기서 예외가 새어 나가면 같은 이벤트의 나머지 리스너와
        // 컨트롤러의 purgeWithdrawnUser()까지 막혀 좀비 계정(M-S1-1)이 된다.
        // 실패해도 refresh 토큰은 purge의 FK CASCADE가, 잔여 행은 스윕 스케줄러가 회수한다.
        try {
            refreshTokenRepository.deleteByUserId(event.userId());
            // 탈퇴 이전 발급된 access token을 즉시 무효화 (A-USER-1).
            // refresh 삭제만으로는 status=INACTIVE 계정의 기존 access token이 만료(30분)까지 유효하므로,
            // 비밀번호 변경과 동일한 무효화 키를 설정해 탈퇴 직후 401 처리되게 한다.
            invalidatePreviousAccessTokens(event.userId());
            accessLogService.log(event.userId(), AccessAction.WITHDRAW, event.ipAddress(), event.userAgent());
        } catch (RuntimeException e) {
            log.error("[WITHDRAW] 토큰·접속로그 정리 실패 — purge/스윕이 회수 예정 userId={}", event.userId(), e);
        }
    }

    // 관리자 정지 - 상태 변경만으로는 이미 발급된 access token이 만료까지 살아 있어
    // 정지된 계정이 최대 30분간 그대로 API를 쓴다. 탈퇴·비밀번호 변경과 같은 무효화 경로를 태운다.
    // best-effort - 여기서 예외가 새어 나가면 정지 자체는 이미 커밋된 뒤라 되돌릴 수 없고,
    // 토큰은 만료로 자연 정리되며 재발급은 상태 검사에서 막힌다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleRestricted(UserRestrictedEvent event) {
        try {
            refreshTokenRepository.deleteByUserId(event.userId());
            invalidatePreviousAccessTokens(event.userId());
        } catch (RuntimeException e) {
            log.error("[ADMIN-RESTRICT] 토큰 무효화 실패 - 기존 토큰이 만료까지 유효할 수 있음 userId={}",
                    event.userId(), e);
        }
    }

    // 관리자 역할 변경 - access token은 발급 시점의 role 클레임으로 권한을 만들어, 역할만 바꾸면 옛 역할의
    // 토큰이 만료까지 @PreAuthorize를 그대로 통과한다. 정지와 같은 무효화 경로를 태운다(2026-09-10 점검 M-1).
    // refresh 재발급은 DB 역할을 읽으므로 새 토큰은 바로 새 역할이 된다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleRoleChanged(UserRoleChangedEvent event) {
        try {
            refreshTokenRepository.deleteByUserId(event.userId());
            invalidatePreviousAccessTokens(event.userId());
        } catch (RuntimeException e) {
            log.error("[ADMIN-ROLE-CHANGE] 토큰 무효화 실패 - 옛 역할 토큰이 만료까지 유효할 수 있음 userId={}",
                    event.userId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handlePasswordChanged(PasswordChangedEvent event) {
        refreshTokenRepository.deleteByUserId(event.userId());
        invalidatePreviousAccessTokens(event.userId());
    }

    // 무효화 기준 시각을 Redis에 저장 — 이 시각 이전 iat를 가진 access token은 401 처리.
    // 비밀번호 변경·회원 탈퇴 공통으로 사용한다. TTL은 access token 만료시간과 동일 — 자연 만료 후엔 메모도 자동 제거.
    private void invalidatePreviousAccessTokens(String userId) {
        redisTemplate.opsForValue().set(
                RedisKeys.PASSWORD_INVALIDATE + userId,
                String.valueOf(System.currentTimeMillis()),
                jwtProperties.getAccessTokenExpiration(), TimeUnit.MILLISECONDS
        );
    }
}

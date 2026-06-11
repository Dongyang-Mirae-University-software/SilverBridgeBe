package kr.silverbridge.main.domain.notification.repository;

import kr.silverbridge.main.domain.notification.entity.FcmToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface FcmTokenRepository extends JpaRepository<FcmToken, Long> {

    // 사용자의 모든 FCM 토큰 조회
    List<FcmToken> findByUserId(String userId);

    // 사용자에게 등록된 FCM 토큰 존재 여부 (긴급 알림 SMS 폴백 판단용 — 토큰 없으면 푸시가 닿지 않음)
    boolean existsByUserId(String userId);

    // 토큰 값으로 조회 (중복 등록 방지)
    Optional<FcmToken> findByToken(String token);

    // 특정 토큰 삭제 (로그아웃·만료 토큰 정리 시)
    // @Transactional 필수 (H-S2-1): 만료 토큰 정리(FcmService.cleanupInvalidTokens)는 @Async 리스너 →
    // 디스패처 → 채널의 무트랜잭션 경로에서 호출된다. 트랜잭션이 없으면 삭제가 TransactionRequiredException으로
    // 실패해 만료 토큰이 영구 잔존하고, hasToken()이 계속 true라 SOS SMS 폴백이 영원히 작동하지 않는다.
    // @Modifying 벌크 DELETE로 select-then-remove 없이 한 문장으로 처리한다.
    @Transactional
    @Modifying
    @Query("DELETE FROM FcmToken t WHERE t.token = :token")
    void deleteByToken(String token);

    // 사용자의 모든 토큰 삭제 (계정 탈퇴 시 CASCADE로 처리되지만 명시적 삭제용)
    void deleteByUserId(String userId);
}
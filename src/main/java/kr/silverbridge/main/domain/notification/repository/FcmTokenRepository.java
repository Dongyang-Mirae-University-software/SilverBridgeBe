package kr.silverbridge.main.domain.notification.repository;

import kr.silverbridge.main.domain.notification.entity.FcmToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface FcmTokenRepository extends JpaRepository<FcmToken, Long> {

    // 사용자의 모든 FCM 토큰 조회
    List<FcmToken> findByUserId(String userId);

    // 토큰 값으로 조회 (중복 등록 방지 + 공유 디바이스 소유자 갱신)
    Optional<FcmToken> findByToken(String token);

    // 본인 소유 토큰만 삭제 (로그아웃 시, L-S2-3 — 타인 토큰 무단 삭제 차단)
    @Transactional
    @Modifying
    @Query("DELETE FROM FcmToken t WHERE t.token = :token AND t.userId = :userId")
    void deleteByTokenAndUserId(String token, String userId);

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

    /**
     * 마지막 사용 시각 갱신. 같은 토큰을 재등록할 때 호출한다.
     *
     * <p>엔티티를 고쳐 변경 감지에 맡기지 않는 이유는, 바뀌는 필드가 없으면 Hibernate가 UPDATE를
     * 아예 내지 않아 {@code updatedAt}이 최초 등록 시각에 멈추기 때문이다. 그러면 "이 토큰이 아직
     * 쓰이는가"를 판단할 근거가 사라진다.</p>
     */
    @Transactional
    @Modifying
    @Query("UPDATE FcmToken t SET t.updatedAt = :now WHERE t.token = :token")
    void touch(String token, OffsetDateTime now);

    /** 상한 적용용 - 마지막 사용이 최근인 순. 넘치는 뒤쪽을 잘라낸다. */
    List<FcmToken> findByUserIdOrderByUpdatedAtDesc(String userId);

    /**
     * 유휴 토큰 정리. {@code updatedAt}이 기준 시각보다 오래된 것을 지운다.
     *
     * @return 삭제된 행 수(운영 로그로 남긴다 - 토큰 삭제는 되돌릴 수 없어 흔적이 필요하다)
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM FcmToken t WHERE t.updatedAt < :threshold")
    int deleteStaleTokens(OffsetDateTime threshold);
}
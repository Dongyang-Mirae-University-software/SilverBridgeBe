package kr.silverbridge.main.domain.notification.repository;

import kr.silverbridge.main.domain.notification.entity.FcmToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FcmTokenRepository extends JpaRepository<FcmToken, Long> {

    // 사용자의 모든 FCM 토큰 조회
    List<FcmToken> findByUserId(String userId);

    // 사용자에게 등록된 FCM 토큰 존재 여부 (긴급 알림 SMS 폴백 판단용 — 토큰 없으면 푸시가 닿지 않음)
    boolean existsByUserId(String userId);

    // 토큰 값으로 조회 (중복 등록 방지)
    Optional<FcmToken> findByToken(String token);

    // 특정 토큰 삭제 (로그아웃 시)
    void deleteByToken(String token);

    // 사용자의 모든 토큰 삭제 (계정 탈퇴 시 CASCADE로 처리되지만 명시적 삭제용)
    void deleteByUserId(String userId);
}
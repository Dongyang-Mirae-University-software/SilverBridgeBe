package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 만료된 Refresh Token을 주기적으로 정리하는 스케줄러
 * 로그아웃·재발급으로 DB에서 제거되지 않은 만료 토큰을 삭제해 테이블 비대화 방지
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenCleanupScheduler {

    private final RefreshTokenRepository refreshTokenRepository;

    // 매일 새벽 3시에 만료된 Refresh Token 삭제
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void deleteExpiredTokens() {
        refreshTokenRepository.deleteByExpiresAtBefore(OffsetDateTime.now());
        log.info("만료된 Refresh Token 정리 완료");
    }
}

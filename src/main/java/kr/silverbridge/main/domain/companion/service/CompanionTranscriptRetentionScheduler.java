package kr.silverbridge.main.domain.companion.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 말벗 대화 내용 30일 보관 정리 (2026-11-11). 매일 04:55 - 다른 일일 정리(03:00·04:00·04:30·04:40·04:45·04:50·05:00)와
 * 겹치지 않는 시각이다. 말벗 스위치와 무관하게 돈다(저장된 글은 꺼져 있어도 지워야 한다). 기분·대화 기록은 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompanionTranscriptRetentionScheduler {

    private final CompanionTranscriptService transcriptService;

    @Scheduled(cron = "0 55 4 * * *", zone = "Asia/Seoul")
    public void cleanup() {
        try {
            int cleared = transcriptService.clearExpired();
            if (cleared > 0) {
                log.info("[COMPANION-TRANSCRIPT-RETENTION] 보관 기간 지난 대화 내용 비움 {}건", cleared);
            }
        } catch (RuntimeException e) {
            log.error("[COMPANION-TRANSCRIPT-RETENTION] 대화 내용 정리 실패, 다음 주기에 재시도: error={}",
                    e.getClass().getSimpleName());
        }
    }
}

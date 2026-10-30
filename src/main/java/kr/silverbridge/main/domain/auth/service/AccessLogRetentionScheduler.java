package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.repository.AccessLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 접속 로그 보관 정리 - 하루 한 번(2026-10-31 전체 점검 D-2, 학생 프로젝트 기준 사용자 결정).
 *
 * <p>① 탈퇴로 익명이 된 행(user_id NULL)의 IP·브라우저 정보를 비우고 ② 보관 기간(기본 90일, 알림 이력과 같음)이 지난 행을
 * 지운다. 익명 보존은 "무엇이 언제 있었나"를 남기려는 것이라 사람을 다시 가리킬 수 있는 값까지 붙들 이유가 없다.</p>
 *
 * <p>04:40인 이유 - 다른 일일 정리(03:00·04:00·04:30·05:00)와 겹치지 않게 한다(스케줄러 풀 3스레드).
 * 예외는 삼킨다 - 한 주기의 실패가 스케줄러를 멈추지 않게(다른 정리 스케줄러와 같은 방침).</p>
 */
@Slf4j
@Component
public class AccessLogRetentionScheduler {

    private final AccessLogRepository accessLogRepository;
    private final int retentionDays;

    public AccessLogRetentionScheduler(AccessLogRepository accessLogRepository,
                                       @Value("${retention.access-log-days:90}") int retentionDays) {
        this.accessLogRepository = accessLogRepository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "0 40 4 * * *", zone = "Asia/Seoul")
    public void cleanup() {
        try {
            int anonymized = accessLogRepository.anonymizeWithdrawn();
            int deleted = retentionDays > 0
                    ? accessLogRepository.deleteOlderThan(OffsetDateTime.now().minusDays(retentionDays))
                    : 0;
            if (anonymized > 0 || deleted > 0) {
                log.info("[ACCESS-LOG-RETENTION] 탈퇴자 IP·UA 비움 {}건, 보관 기간 지난 로그 삭제 {}건 (기준 {}일)",
                        anonymized, deleted, retentionDays);
            }
        } catch (RuntimeException e) {
            log.error("[ACCESS-LOG-RETENTION] 접속 로그 정리 실패, 다음 주기에 재시도", e);
        }
    }
}

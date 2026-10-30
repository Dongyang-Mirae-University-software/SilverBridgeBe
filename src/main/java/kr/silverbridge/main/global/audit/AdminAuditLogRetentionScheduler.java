package kr.silverbridge.main.global.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 관리자 감사 로그 보관 정리 - 하루 한 번(2026-10-31 전체 점검 D-2, 학생 프로젝트 기준 사용자 결정).
 *
 * <p>detail에는 강제 탈퇴한 회원의 이름·이메일, 이름 변경 전후 값, 정지 사유가 들어 있다. 누가 무엇을 바꿨는지 추적할 기간
 * (기본 1년 - 접속 로그 90일보다 길게, 관리자 조작은 드물고 책임 추적이 목적이라)이 지나면 행째 지운다.</p>
 *
 * <p>04:45 - 다른 일일 정리와 겹치지 않게. 예외는 삼킨다.</p>
 */
@Slf4j
@Component
public class AdminAuditLogRetentionScheduler {

    private final AdminAuditLogRepository repository;
    private final int retentionDays;

    public AdminAuditLogRetentionScheduler(AdminAuditLogRepository repository,
                                           @Value("${retention.admin-audit-log-days:365}") int retentionDays) {
        this.repository = repository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "0 45 4 * * *", zone = "Asia/Seoul")
    public void cleanup() {
        if (retentionDays <= 0) {
            return;
        }
        try {
            int deleted = repository.deleteOlderThan(OffsetDateTime.now().minusDays(retentionDays));
            if (deleted > 0) {
                log.info("[ADMIN-AUDIT-RETENTION] 보관 기간 지난 감사 로그 삭제 {}건 (기준 {}일)", deleted, retentionDays);
            }
        } catch (RuntimeException e) {
            log.error("[ADMIN-AUDIT-RETENTION] 감사 로그 정리 실패, 다음 주기에 재시도", e);
        }
    }
}

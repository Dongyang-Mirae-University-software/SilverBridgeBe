package kr.silverbridge.main.domain.checkin.service;

import kr.silverbridge.main.domain.checkin.repository.CheckinNotifyLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 안부 말벗 보관 기간 정리(2026-11-01 점검 M-4, 사용자 결정 - D-2와 같은 학생 프로젝트 기준).
 *
 * <p>발송 기록(피보호자·보호자 알림 선점 행)은 {@code retention.checkin-log-days}(기본 90일), 대화·결과는
 * {@code retention.checkin-result-days}(기본 365일) 지나면 지운다. 결과 보관은 평소 대비 기준(14일)·보호자 조회(60일)보다
 * 길어야 한다. 0 이하면 그 삭제만 끈다. 매일 05:10 KST - 다른 일일 정리(03:00~05:00)·매시 :15와 겹치지 않는다.
 * 말벗이 꺼진 서버에서도 돈다(행이 없으면 아무것도 지우지 않는다).</p>
 */
@Slf4j
@Component
public class CheckinRetentionScheduler {

    private final CheckinNotifyLogRepository repository;
    private final CheckinClock clock;
    private final int logDays;
    private final int resultDays;

    public CheckinRetentionScheduler(CheckinNotifyLogRepository repository, CheckinClock clock,
                                     @Value("${retention.checkin-log-days:90}") int logDays,
                                     @Value("${retention.checkin-result-days:365}") int resultDays) {
        this.repository = repository;
        this.clock = clock;
        this.logDays = logDays;
        this.resultDays = resultDays;
    }

    @Scheduled(cron = "0 10 5 * * *", zone = "Asia/Seoul")
    public void cleanup() {
        try {
            LocalDate today = clock.now().toLocalDate();
            int prompts = 0;
            int alerts = 0;
            int sessions = 0;
            if (logDays > 0) {
                prompts = repository.deletePromptLogsBefore(today.minusDays(logDays));
                alerts = repository.deleteGuardianAlertLogsBefore(today.minusDays(logDays));
            }
            if (resultDays > 0) {
                sessions = repository.deleteSessionsBefore(today.minusDays(resultDays));
            }
            if (prompts + alerts + sessions > 0) {
                log.info("[CHECKIN-RETENTION] 안부 알림 기록 {}건·보호자 알림 기록 {}건·대화(결과 포함) {}건 삭제 (기준 {}일·{}일)",
                        prompts, alerts, sessions, logDays, resultDays);
            }
        } catch (RuntimeException e) {
            log.error("[CHECKIN-RETENTION] 정리 실패, 다음 주기에 재시도: error={}", e.getClass().getSimpleName());
        }
    }
}

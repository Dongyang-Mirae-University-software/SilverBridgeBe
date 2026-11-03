package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSessionStatus;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 오래 멈춘 안부 대화 마감(5분 주기, 2026-11-04). 어르신이 대답 도중 앱을 닫으면 대화가 OPEN으로 남아 결과(기분·위험)가
 * 저장되지 않는다 - {@code abandonAfter}(10분) 동안 활동이 없으면 AI에서 결과를 받아 닫는다(종료 이유 NO_RESPONSE).
 *
 * <p>AI에 대화가 없으면(재시작) LOST로 닫는다. 그 밖의 AI 실패는 <b>첫 실패에서 이번 주기를 멈춘다</b> - AI가 죽어 있을 때
 * 1건당 최대 90초씩 스케줄러 스레드(공용 풀 3)를 붙들면 복약·AI 재접속이 밀린다. 1회 최대 {@code abandonBatchSize}건.
 * 스위치가 꺼져 있으면 돌지 않는다(켤 때 남은 대화를 닫는다).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompanionAbandonScheduler {

    private final CompanionProperties properties;
    private final CompanionSessionRepository sessionRepository;
    private final CompanionService companionService;

    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Seoul")
    public void closeAbandoned() {
        if (!properties.isUsable()) {
            return;
        }
        OffsetDateTime cutoff = OffsetDateTime.now(CompanionService.KST).minus(properties.getAbandonAfter());
        List<CompanionSession> stale = sessionRepository.findStale(CompanionSessionStatus.OPEN, cutoff,
                PageRequest.of(0, properties.getAbandonBatchSize()));
        int closed = 0;
        for (CompanionSession session : stale) {
            try {
                if (companionService.closeAbandoned(session)) {
                    closed++;
                }
            } catch (CustomException e) {
                if (e.getErrorCode() == ErrorCode.COMPANION_SESSION_LOST) {
                    closed++;
                    continue;
                }
                log.warn("[COMPANION-ABANDON] 마감 실패 - 이번 주기 중단: sessionId={}, code={}",
                        session.getId(), e.getErrorCode());
                break;
            } catch (RuntimeException e) {
                log.error("[COMPANION-ABANDON] 마감 중 오류 - 이번 주기 중단: sessionId={}, error={}",
                        session.getId(), e.getClass().getSimpleName());
                break;
            }
        }
        if (closed > 0) {
            log.info("[COMPANION-ABANDON] 멈춘 대화 마감: closed={}, found={}", closed, stale.size());
        }
    }
}

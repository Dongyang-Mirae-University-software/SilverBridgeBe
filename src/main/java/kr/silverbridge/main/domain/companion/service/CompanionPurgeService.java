package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.client.AiCompanionClient;
import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.entity.CompanionPurgeRequest;
import kr.silverbridge.main.domain.companion.repository.CompanionPurgeRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 탈퇴한 피보호자의 AI 쪽 말벗 데이터 삭제(결정 C, 2026-11-04). 대기열({@code companion_purge_request})에 있는 ID마다
 * AI {@code DELETE /companion/wards/{wardId}/data}를 부르고, 성공하면 대기열에서 지운다.
 *
 * <p>탈퇴·역할 변경 직후 한 번(비동기) + 매시 35분 재시도. 재시도는 시도하지 않은 것·마지막 시도가 오래된 것부터 최대
 * {@code purgeBatchSize}건이며 <b>첫 실패에서 멈춘다</b> - AI가 죽어 있을 때 건마다 제한 시간을 다 쓰며 스케줄러 스레드를 붙들지 않는다. 주소·키가 없으면 대기열만
 * 남긴다(스위치 {@code enabled}와는 무관 - 꺼 둔 동안 탈퇴한 사람도 지워야 한다). 로그에는 ID·시도 횟수·예외 클래스명만.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanionPurgeService {

    /** 이 횟수마다 실패를 ERROR로 올린다(매시 재시도 = 하루). */
    static final int ALERT_EVERY_ATTEMPTS = 24;

    private final AiCompanionClient aiClient;
    private final CompanionProperties properties;
    private final CompanionPurgeRequestRepository purgeRequestRepository;

    /** 한 건 시도. 성공하면 true(대기열에서 지움). */
    public boolean tryPurge(String wardId) {
        if (!properties.isConfigured()) {
            log.info("[COMPANION-PURGE] 말벗 AI 주소·키 미설정 - 삭제 대기: wardId={}", wardId);
            return false;
        }
        try {
            aiClient.deleteWardData(wardId);
        } catch (RuntimeException e) {
            purgeRequestRepository.findById(wardId).ifPresent(request -> {
                request.recordFailure(OffsetDateTime.now(CompanionService.KST));
                purgeRequestRepository.save(request);
                // 매시 재시도라 하루(24회)마다 한 번 ERROR로 올린다 - WARN만으로는 오래 남은 건이 묻힌다(점검 L-2)
                if (request.getAttempts() % ALERT_EVERY_ATTEMPTS == 0) {
                    log.error("[COMPANION-PURGE-STUCK] AI 말벗 데이터 삭제가 계속 실패: wardId={}, attempts={}",
                            wardId, request.getAttempts());
                }
            });
            log.warn("[COMPANION-PURGE-FAILED] AI 말벗 데이터 삭제 실패 - 재시도 예정: wardId={}, error={}",
                    wardId, e.getClass().getSimpleName());
            return false;
        }
        purgeRequestRepository.deleteById(wardId);
        log.info("[COMPANION-PURGE] AI 말벗 데이터 삭제 완료: wardId={}", wardId);
        return true;
    }

    @Scheduled(cron = "0 35 * * * *", zone = "Asia/Seoul")
    public void retryPending() {
        if (!properties.isConfigured()) {
            return;
        }
        List<CompanionPurgeRequest> pending = purgeRequestRepository.findRetryOrder(
                PageRequest.of(0, properties.getPurgeBatchSize()));
        for (CompanionPurgeRequest request : pending) {
            if (!tryPurge(request.getWardId())) {
                break;
            }
        }
    }
}

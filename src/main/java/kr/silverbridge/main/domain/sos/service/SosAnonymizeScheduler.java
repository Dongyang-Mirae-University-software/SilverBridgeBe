package kr.silverbridge.main.domain.sos.service;

import kr.silverbridge.main.domain.sos.repository.SosEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 탈퇴한 피보호자의 익명 SOS 이력에서 위치 문구를 지운다 - 하루 한 번(2026-10-31 전체 점검 D-2, 학생 프로젝트 기준 사용자 결정).
 *
 * <p>SOS 이력은 감사 목적으로 익명 보존한다(FK SET NULL, policy-auth). 다만 {@code location}은 집 안 위치를 적은 자유
 * 문구(최대 100자)라 사람을 다시 가리킬 수 있어 남기지 않는다. 04:50 - 다른 일일 정리와 겹치지 않게. 예외는 삼킨다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SosAnonymizeScheduler {

    private final SosEventRepository sosEventRepository;

    @Scheduled(cron = "0 50 4 * * *", zone = "Asia/Seoul")
    public void anonymize() {
        try {
            int cleared = sosEventRepository.clearLocationOfWithdrawn();
            if (cleared > 0) {
                log.info("[SOS-ANONYMIZE] 탈퇴 피보호자 익명 이력의 위치 문구 비움 {}건", cleared);
            }
        } catch (RuntimeException e) {
            log.error("[SOS-ANONYMIZE] 익명 이력 위치 정리 실패, 다음 주기에 재시도", e);
        }
    }
}
